use super::*;
use std::{
    ops::Deref,
    sync::{MutexGuard, Weak},
};

static NEXT_DB_HANDLE: AtomicI64 = AtomicI64::new(1);
static DB_REGISTRY: OnceLock<Mutex<HashMap<jlong, Arc<VotingDbHandle>>>> = OnceLock::new();
static DB_BY_KEY: OnceLock<Mutex<HashMap<DbKey, Weak<VotingDbHandle>>>> = OnceLock::new();

#[derive(Clone, Eq, Hash, PartialEq)]
struct DbKey {
    path: String,
    wallet_id: String,
}

pub(super) struct VotingDbHandle {
    db: VotingDb,
    // VoteTreeSync owns only its synchronous tree-client cache and protects
    // that cache internally. JNI vote-tree entrypoints still hold access_mutex
    // before calling it so DB writes and tree-client state changes are
    // serialized for shared managed handles.
    pub(super) tree_sync: VoteTreeSync,
    access_mutex: Mutex<()>,
}

impl VotingDbHandle {
    fn open(path: &str, wallet_id: &str) -> anyhow::Result<Self> {
        let db = VotingDb::open(path).map_err(|e| anyhow!("VotingDb::open failed: {}", e))?;
        db.set_wallet_id(wallet_id);

        Ok(Self {
            db,
            tree_sync: VoteTreeSync::new(),
            access_mutex: Mutex::new(()),
        })
    }

    pub(super) fn access_lock(&self) -> anyhow::Result<MutexGuard<'_, ()>> {
        self.access_mutex
            .lock()
            .map_err(|_| anyhow!("voting DB access mutex poisoned"))
    }
}

impl Deref for VotingDbHandle {
    type Target = VotingDb;

    fn deref(&self) -> &Self::Target {
        &self.db
    }
}

fn registry() -> &'static Mutex<HashMap<jlong, Arc<VotingDbHandle>>> {
    DB_REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn db_by_key() -> &'static Mutex<HashMap<DbKey, Weak<VotingDbHandle>>> {
    DB_BY_KEY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> anyhow::Result<jlong> {
    NEXT_DB_HANDLE
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
        .map_err(|_| anyhow!("voting DB handle space exhausted"))
}

pub(super) fn db_from_handle(handle: jlong) -> anyhow::Result<Arc<VotingDbHandle>> {
    if handle <= 0 {
        return Err(anyhow!("Voting DB handle must be positive, got {handle}"));
    }

    registry()
        .lock()
        .map_err(|_| anyhow!("voting DB registry mutex poisoned"))?
        .get(&handle)
        .cloned()
        .ok_or_else(|| anyhow!("Voting DB handle is closed or unknown: {handle}"))
}

fn open_managed_db(path: &str, wallet_id: &str) -> anyhow::Result<Arc<VotingDbHandle>> {
    if path == ":memory:" {
        return Ok(Arc::new(VotingDbHandle::open(path, wallet_id)?));
    }

    let key = DbKey {
        path: path.to_string(),
        wallet_id: wallet_id.to_string(),
    };
    let mut dbs = db_by_key()
        .lock()
        .map_err(|_| anyhow!("voting DB key registry mutex poisoned"))?;
    dbs.retain(|_, db| db.strong_count() > 0);

    if let Some(db) = dbs.get(&key).and_then(Weak::upgrade) {
        return Ok(db);
    }

    let db = Arc::new(VotingDbHandle::open(path, wallet_id)?);
    dbs.insert(key, Arc::downgrade(&db));
    Ok(db)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_openVotingDbNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_path: JString<'local>,
    wallet_id: JString<'local>,
) -> jlong {
    let res = catch_unwind(&mut env, |env| {
        let path = java_string_to_rust(env, &db_path)?;
        let wallet_id = java_string_to_rust(env, &wallet_id)?;
        if wallet_id.is_empty() {
            return Err(anyhow!("walletId must not be empty"));
        }

        let db = open_managed_db(&path, &wallet_id)?;
        let handle = next_handle()?;
        registry()
            .lock()
            .map_err(|_| anyhow!("voting DB registry mutex poisoned"))?
            .insert(handle, db);

        Ok(handle)
    });
    unwrap_exc_or(&mut env, res, 0)
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_cash_z_ecc_android_sdk_internal_jni_VotingRustBackend_closeVotingDbNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _: JClass<'local>,
    db_handle: jlong,
) {
    let res = catch_unwind(&mut env, |_| {
        if db_handle > 0 {
            registry()
                .lock()
                .map_err(|_| anyhow!("voting DB registry mutex poisoned"))?
                .remove(&db_handle);
        }
        Ok(())
    });
    unwrap_exc_or(&mut env, res, ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        time::{SystemTime, UNIX_EPOCH},
    };

    #[test]
    fn managed_db_reuses_access_lock_for_same_path_and_wallet() {
        let db_path = unique_db_path();
        let db_path_str = db_path.to_str().expect("test db path is valid UTF-8");
        let first = open_managed_db(db_path_str, "wallet-1").expect("first DB open");
        let second = open_managed_db(db_path_str, "wallet-1").expect("second DB open");

        assert!(Arc::ptr_eq(&first, &second));
        let guard = first.access_lock().expect("first access lock");
        assert!(second.access_mutex.try_lock().is_err());
        drop(guard);

        drop(first);
        drop(second);
        let _ = fs::remove_file(db_path);
    }

    fn unique_db_path() -> std::path::PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("current time is after UNIX_EPOCH")
            .as_nanos();
        std::env::temp_dir().join(format!(
            "zcash-android-voting-db-test-{}-{nanos}.sqlite",
            std::process::id()
        ))
    }
}
