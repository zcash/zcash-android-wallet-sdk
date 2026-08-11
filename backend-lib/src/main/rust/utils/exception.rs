// Copyright 2018 The Exonum Team
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use jni::JNIEnv;
use jni::objects::{JObject, JThrowable};
use std::any::Any;
use std::thread;
use tracing::error;

type ExceptionResult<T> = thread::Result<anyhow::Result<T>>;

// Returns value or "throws" exception. `error_val` is returned, because exception will be thrown
// at the Java side. So this function should be used only for the `panic::catch_unwind` result.
pub fn unwrap_exc_or<T>(env: &mut JNIEnv, res: ExceptionResult<T>, error_val: T) -> T {
    match res {
        Ok(val) => {
            match val {
                Ok(val) => val,
                Err(jni_error) => {
                    // Do nothing if there is a pending Java-exception that will be thrown
                    // automatically by the JVM when the native method returns. Typed
                    // exceptions raised through `throw_object` below rely on this check to
                    // win over the generic `RuntimeException`.
                    if !env.exception_check().unwrap() {
                        // Throw a Java exception manually in case of an internal error.
                        throw(env, &jni_error.to_string())
                    }
                    error_val
                }
            }
        }
        Err(ref e) => {
            throw(env, &any_to_string(e));
            error_val
        }
    }
}

// // Same as `unwrap_exc_or` but returns default value.
// pub fn unwrap_exc_or_default<T: Default>(env: &JNIEnv, res: ExceptionResult<T>) -> T {
//     unwrap_exc_or(env, res, T::default())
// }

// Calls a corresponding `JNIEnv` method, so exception will be thrown when execution returns to
// the Java side.
fn throw(env: &mut JNIEnv, description: &str) {
    // We cannot throw exception from this function, so errors should be written in log instead.
    let exception = match env.find_class("java/lang/RuntimeException") {
        Ok(val) => val,
        Err(e) => {
            error!("Unable to find 'RuntimeException' class: {}", e.to_string());
            return;
        }
    };
    if let Err(e) = env.throw_new(exception, description) {
        error!("Unable to find 'RuntimeException' class: {}", e.to_string());
    }
}

/// Throws the exception object built by `construct` on the JVM, so that a typed exception —
/// rather than the generic `RuntimeException` from `throw` above — is pending when the native
/// method returns. `class` names the exception class, for logging only; `construct` typically
/// calls `JNIEnv::new_object` (`JNIEnv::throw_new` cannot be used when the constructor takes
/// more than a message string).
///
/// This helper and `unwrap_exc_or` share a contract: `unwrap_exc_or` skips its own throw
/// whenever an exception is already pending, so a successful call here wins over the generic
/// path. When constructing or throwing fails, the failure usually leaves its *own* exception
/// (e.g. `NoClassDefFoundError`) pending, which would otherwise suppress the generic fallback
/// and surface an unrelated JVM error instead of the original one — so it is cleared here,
/// and the generic path reports the original error.
pub fn throw_object<'local, F>(env: &mut JNIEnv<'local>, class: &str, construct: F)
where
    F: FnOnce(&mut JNIEnv<'local>) -> Result<JObject<'local>, jni::errors::Error>,
{
    let thrown = construct(env).and_then(|exception| env.throw(JThrowable::from(exception)));
    if let Err(e) = thrown {
        error!("Unable to throw '{}': {}", class, e);
        let _ = env.exception_clear();
    }
}

// Tries to get meaningful description from panic-error.
pub fn any_to_string(any: &Box<dyn Any + Send>) -> String {
    if let Some(s) = any.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = any.downcast_ref::<String>() {
        s.clone()
    } else if let Some(error) = any.downcast_ref::<Box<dyn std::error::Error + Send>>() {
        error.to_string()
    } else {
        "Unknown error occurred".to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::error::Error;
    use std::fmt;
    use std::panic;

    #[test]
    fn str_any() {
        let string = "Static string (&str)";
        let error = panic_error(string);
        assert_eq!(string, any_to_string(&error));
    }

    #[test]
    fn string_any() {
        let string = "Owned string (String)".to_owned();
        let error = panic_error(string.clone());
        assert_eq!(string, any_to_string(&error));
    }

    #[test]
    fn box_error_any() {
        let error: Box<dyn Error + Send> = Box::new("e".parse::<i32>().unwrap_err());
        let description = error.to_string();
        let error = panic_error(error);
        assert_eq!(description, any_to_string(&error));
    }

    #[test]
    fn unknown_any() {
        let error = panic_any_error(1u32);
        assert_eq!("Unknown error occurred", any_to_string(&error));
    }

    fn panic_error<T: fmt::Display + Send + 'static>(val: T) -> Box<dyn Any + Send> {
        panic::catch_unwind(panic::AssertUnwindSafe(|| panic!("{}", val))).unwrap_err()
    }

    // Panics with `val` itself as the payload. `panic_error` cannot reach
    // `any_to_string`'s fallback branch: `panic!("{}", val)` formats the value, so the
    // payload is always a `String` and downcasting succeeds. Only a payload that is
    // neither `&str`, `String`, nor `Box<dyn Error + Send>` exercises the fallback.
    fn panic_any_error<T: Any + Send + 'static>(val: T) -> Box<dyn Any + Send> {
        panic::catch_unwind(panic::AssertUnwindSafe(|| panic::panic_any(val))).unwrap_err()
    }
}
