use core::slice;
use std::convert::TryFrom;
use std::panic::{self, AssertUnwindSafe, UnwindSafe};
use std::thread;

use jni::{
    JNIEnv,
    descriptors::Desc,
    errors::{Error as JNIError, Result as JNIResult},
    objects::{JByteArray, JClass, JObject, JObjectArray, JString},
    sys::jsize,
};

pub(crate) mod exception;
pub(crate) mod target_ndk;
pub(crate) mod trace;

/// Invokes a closure, capturing the cause of an unwinding panic if one occurs.
///
/// This asserts that `&mut JNIEnv` is unwind-safe. See [this issue] for context.
///
/// [this issue]: https://github.com/jni-rs/jni-rs/issues/432
pub fn catch_unwind<F: FnOnce(&mut JNIEnv) -> R + UnwindSafe, R>(
    env: &mut JNIEnv,
    f: F,
) -> thread::Result<R> {
    let mut wrapped_env = AssertUnwindSafe(env);

    panic::catch_unwind({
        let mut other_wrapped_env = AssertUnwindSafe(&mut wrapped_env);

        move || (f)(***other_wrapped_env)
    })
}

pub(crate) fn java_bytes_to_rust(env: &JNIEnv, jbytes: &JByteArray) -> anyhow::Result<Vec<u8>> {
    Ok(env.convert_byte_array(jbytes)?)
}

pub(crate) fn java_nullable_bytes_to_rust(
    env: &JNIEnv,
    jbytes: &JByteArray,
) -> anyhow::Result<Option<Vec<u8>>> {
    (!jbytes.is_null())
        .then(|| java_bytes_to_rust(env, jbytes))
        .transpose()
}

pub(crate) fn java_string_to_rust(env: &mut JNIEnv, jstring: &JString) -> anyhow::Result<String> {
    Ok(env.get_string(jstring)?.into())
}

pub(crate) fn java_nullable_string_to_rust(
    env: &mut JNIEnv,
    jstring: &JString,
) -> anyhow::Result<Option<String>> {
    (!jstring.is_null())
        .then(|| java_string_to_rust(env, jstring))
        .transpose()
}

/// Decodes a Java `byte[][]` (a `JObjectArray` whose elements are `JByteArray`s) into a
/// `Vec<Vec<u8>>`.
pub(crate) fn decode_byte_array_list(
    env: &mut JNIEnv,
    list: &JObjectArray,
) -> anyhow::Result<Vec<Vec<u8>>> {
    let count = env.get_array_length(list)?;
    let mut out = Vec::with_capacity(count as usize);
    for i in 0..count {
        let obj = env.get_object_array_element(list, i)?;
        out.push(java_bytes_to_rust(env, &JByteArray::from(obj))?);
    }
    Ok(out)
}

pub(crate) fn rust_bytes_to_java<'a>(env: &JNIEnv<'a>, data: &[u8]) -> JNIResult<JByteArray<'a>> {
    // SAFETY: jbyte (i8) has the same size and alignment as u8, and a well-defined
    // twos-complement representation with no "trap representations".
    let buf = unsafe { slice::from_raw_parts(data.as_ptr().cast(), data.len()) };
    let jret = env.new_byte_array(data.len() as jsize)?;
    env.set_byte_array_region(&jret, 0, buf)?;
    Ok(jret)
}

pub(crate) fn rust_vec_to_java<'a, T, U, V, F>(
    env: &mut JNIEnv<'a>,
    data: Vec<T>,
    element_class: U,
    element_map: F,
) -> JNIResult<JObjectArray<'a>>
where
    U: Desc<'a, JClass<'a>>,
    V: Into<JObject<'a>>,
    F: Fn(&mut JNIEnv<'a>, T) -> JNIResult<V>,
{
    let length = jsize::try_from(data.len())
        // not quite the right error but close enough
        .map_err(|_| JNIError::WrongJValueType("jsize", "usize"))?;
    let mut iter = data.into_iter().enumerate();

    if let Some((_, elem)) = iter.next() {
        let jelem = element_map(env, elem)?;
        // All array entries, and in particular the one at index 0, are initialized to jelem.into().
        let jret = env.new_object_array(length, element_class, jelem.into())?;

        // All array entries after the first will be set.
        for (i, elem) in iter {
            let jelem = element_map(env, elem)?;
            env.set_object_array_element(
                &jret,
                jsize::try_from(i).expect("i fits in jsize"),
                jelem.into(),
            )?;
        }
        Ok(jret)
    } else {
        // It is okay to use null for initial_element here even if the Kotlin type is non-nullable,
        // because the array is empty.
        Ok(env.new_object_array(0, element_class, JObject::null())?)
    }
}

// // 2D array
// pub(crate) fn rust_vec_to_java_2d<'a, T, V, F, G>(
//     env: &JNIEnv<'a>,
//     data1: Vec<T>,
//     data2: Vec<T>,
//     element_map: F,
//     empty_element: G,
// ) -> JNIResult<JObjectArray<'a>>
// where
//     V: std::ops::Deref<Target = JObject<'a>>,
//     F: Fn(&JNIEnv<'a>, T) -> JNIResult<V>,
//     G: Fn(&JNIEnv<'a>) -> JNIResult<V>,
// {
//     let jempty = empty_element(env)?;
//     let outer =
//         env.new_object_array(data1.len() as jsize, "[Ljava/lang/String;", JObject::null())?;

//     for (i, (elem1, elem2)) in data1.into_iter().zip(data2.into_iter()).enumerate() {
//         let inner = env.new_object_array(2 as jsize, "java/lang/String", *jempty)?;
//         let jelem1 = element_map(env, elem1)?;
//         let jelem2 = element_map(env, elem2)?;
//         env.set_object_array_element(inner, 0 as jsize, *jelem1)?;
//         env.set_object_array_element(inner, 1 as jsize, *jelem2)?;
//         env.set_object_array_element(outer, i as jsize, inner)?;
//     }
//     Ok(outer)
// }
