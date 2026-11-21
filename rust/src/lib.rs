use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::JNIEnv;
use serde::Serialize;
use std::str::FromStr;
use std::sync::Mutex;
use taskchampion::{Operation, Operations, Replica, ServerConfig, Status, StorageConfig, Tag, Uuid};

struct ReplicaHandle {
    sync_url: String,
    client_id: Uuid,
    encryption_secret: Vec<u8>,
    replica: Replica,
}

#[derive(Serialize)]
struct TaskInfo {
    uuid: String,
    description: String,
    tags: Vec<String>,
    project: Option<String>,
    entry: Option<String>,
}

#[derive(Serialize)]
struct TaskList {
    tasks: Vec<TaskInfo>,
    contexts: Vec<String>,
}

fn throw(env: &mut JNIEnv, class: &str, msg: &str) {
    let _ = env.throw_new(class, msg.replace('\u{0}', " "));
}

fn env_str(env: &mut JNIEnv, s: JString) -> Result<String, String> {
    env.get_string(&s)
        .map(|s| s.into())
        .map_err(|_| "invalid Java string".to_string())
}

fn lock(env: &mut JNIEnv, ptr: jlong) -> Option<std::sync::MutexGuard<'static, ReplicaHandle>> {
    if ptr == 0 {
        throw(env, "java/lang/RuntimeException", "replica not open");
        return None;
    }
    let handle = unsafe { &mut *(ptr as usize as *mut Mutex<ReplicaHandle>) };
    match handle.lock() {
        Ok(g) => Some(g),
        Err(_) => {
            throw(env, "java/lang/RuntimeException", "replica lock poisoned");
            None
        }
    }
}

fn err_string(e: taskchampion::Error) -> String {
    let mut parts = vec![format!("error: {e}")];
    if let taskchampion::Error::Other(e) = &e {
        for cause in e.chain().skip(1) {
            parts.push(cause.to_string());
        }
    }
    parts.join(": ")
}

#[no_mangle]
pub extern "system" fn Java_dev_ruimdetijd_aircav_AirCavLib_open(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
    sync_url: JString,
    client_id: JString,
    encryption_secret: JString,
) -> jlong {
    match open_inner(
        &mut env,
        data_dir,
        sync_url,
        client_id,
        encryption_secret,
    ) {
        Ok(ptr) => ptr as jlong,
        Err(e) => {
            throw(&mut env, "java/lang/RuntimeException", &e);
            0
        }
    }
}

fn open_inner(
    env: &mut JNIEnv,
    data_dir: JString,
    sync_url: JString,
    client_id: JString,
    encryption_secret: JString,
) -> Result<usize, String> {
    let data_dir: String = env_str(env, data_dir)?;
    let sync_url = env_str(env, sync_url)?;
    let client_id = env_str(env, client_id)?;
    let encryption_secret = env_str(env, encryption_secret)?;

    let client_id = Uuid::parse_str(&client_id).map_err(|_| "invalid client id".to_string())?;

    let taskdb_dir = std::path::PathBuf::from(&data_dir);
    std::fs::create_dir_all(&taskdb_dir).map_err(|e| format!("cannot create data dir: {e}"))?;

    let storage_config = StorageConfig::OnDisk {
        taskdb_dir,
        create_if_missing: true,
        access_mode: taskchampion::storage::AccessMode::ReadWrite,
    };
    let storage = storage_config
        .into_storage()
        .map_err(|e| format!("cannot open storage: {e}"))?;
    let replica = Replica::new(storage);

    let handle = Box::new(Mutex::new(ReplicaHandle {
        sync_url,
        client_id,
        encryption_secret: encryption_secret.into_bytes(),
        replica,
    }));
    Ok(Box::into_raw(handle) as usize)
}

#[no_mangle]
pub extern "system" fn Java_dev_ruimdetijd_aircav_AirCavLib_listTasks(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) -> jstring {
    let Some(mut h) = lock(&mut env, ptr) else {
        return std::ptr::null_mut();
    };
    let result: Result<String, String> = (|| {
        let pending = h.replica.pending_tasks().map_err(err_string)?;
        let mut tasks: Vec<TaskInfo> = Vec::with_capacity(pending.len());
        for t in pending {
            let tags = t
                .get_tags()
                .filter(|tag| !tag.is_synthetic())
                .map(|tag| tag.to_string())
                .collect::<Vec<String>>();
            let uuid = t.get_uuid().to_string();
            let description = t.get_description().to_string();
            let data = t.into_task_data();
            let project = data.get("project").map(|p| p.to_string());
            let entry = data.get("entry").map(|e| e.to_string());
            tasks.push(TaskInfo {
                uuid,
                description,
                tags,
                project,
                entry,
            });
        }
        let mut contexts = tasks
            .iter()
            .flat_map(|t| t.tags.iter().cloned())
            .collect::<Vec<String>>();
        contexts.sort();
        contexts.dedup();
        serde_json::to_string(&TaskList { tasks, contexts })
            .map_err(|e| format!("encode error: {e}"))
    })();
    match result {
        Ok(json) => match env.new_string(json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(e) => {
            throw(&mut env, "java/lang/RuntimeException", &e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_ruimdetijd_aircav_AirCavLib_addTask(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    description: JString,
    tags_json: JString,
    project: JString,
) {
    let Some(mut h) = lock(&mut env, ptr) else {
        return;
    };
    let result: Result<(), String> = (|| {
        let description = env_str(&mut env, description)?;
        let tags_raw = env_str(&mut env, tags_json)?;
        let tags: Vec<String> =
            serde_json::from_str(&tags_raw).map_err(|_| "invalid tags json".to_string())?;
        if description.trim().is_empty() {
            return Err("empty description".to_string());
        }
        let project_raw = env_str(&mut env, project)?;
        let project_name = project_raw.trim().to_string();
        let mut ops = Operations::new();
        ops.push(Operation::UndoPoint);
        let uuid = Uuid::new_v4();
        let mut t = h
            .replica
            .create_task(uuid, &mut ops)
            .map_err(err_string)?;
        t.set_description(description.trim().to_string(), &mut ops)
            .map_err(err_string)?;
        t.set_status(Status::Pending, &mut ops)
            .map_err(err_string)?;
        t.set_entry(Some(chrono::Utc::now()), &mut ops)
            .map_err(err_string)?;
        for tag in tags {
            let tag = Tag::from_str(&tag).map_err(|_| format!("invalid tag: {tag}"))?;
            t.add_tag(&tag, &mut ops).map_err(err_string)?;
        }
        if !project_name.is_empty() {
            let mut data = t.into_task_data();
            data.update("project", Some(project_name.clone()), &mut ops);
        }
        h.replica.commit_operations(ops).map_err(err_string)?;
        Ok(())
    })();
    if let Err(e) = result {
        throw(&mut env, "java/lang/RuntimeException", &e);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_ruimdetijd_aircav_AirCavLib_completeTask(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    uuid_str: JString,
) -> jboolean {
    let Some(mut h) = lock(&mut env, ptr) else {
        return false.into();
    };
    let result: Result<bool, String> = (|| {
        let uuid_str = env_str(&mut env, uuid_str)?;
        let uuid = Uuid::parse_str(&uuid_str).map_err(|_| "invalid uuid".to_string())?;
        let mut ops = Operations::new();
        ops.push(Operation::UndoPoint);
        let mut t = match h.replica.get_task(uuid).map_err(err_string)? {
            Some(t) => t,
            None => return Ok(false),
        };
        t.done(&mut ops).map_err(err_string)?;
        h.replica.commit_operations(ops).map_err(err_string)?;
        Ok(true)
    })();
    match result {
        Ok(done) => done.into(),
        Err(e) => {
            throw(&mut env, "java/lang/RuntimeException", &e);
            false.into()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_ruimdetijd_aircav_AirCavLib_removeTask(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    uuid_str: JString,
) {
    let Some(mut h) = lock(&mut env, ptr) else {
        return;
    };
    let result: Result<(), String> = (|| {
        let uuid_str = env_str(&mut env, uuid_str)?;
        let uuid = Uuid::parse_str(&uuid_str).map_err(|_| "invalid uuid".to_string())?;
        let mut ops = Operations::new();
        ops.push(Operation::UndoPoint);
        if let Some(mut t) = h.replica.get_task(uuid).map_err(err_string)? {
            t.set_status(Status::Deleted, &mut ops)
                .map_err(err_string)?;
        }
        h.replica.commit_operations(ops).map_err(err_string)?;
        Ok(())
    })();
    if let Err(e) = result {
        throw(&mut env, "java/lang/RuntimeException", &e);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_ruimdetijd_aircav_AirCavLib_sync(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    let Some(mut h) = lock(&mut env, ptr) else {
        return;
    };
    let result: Result<(), String> = (|| {
        let mut server = ServerConfig::Remote {
            url: h.sync_url.clone(),
            client_id: h.client_id,
            encryption_secret: h.encryption_secret.clone(),
        }
        .into_server()
        .map_err(|e| format!("cannot create server: {e}"))?;
        let res = h.replica.sync(&mut server, true);
        res.map_err(|e| format!("sync failed: {}", err_string(e)))
    })();
    if let Err(e) = result {
        throw(&mut env, "java/lang/RuntimeException", &e);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_ruimdetijd_aircav_AirCavLib_close(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        unsafe { drop(Box::from_raw(ptr as usize as *mut Mutex<ReplicaHandle>)) };
    }
}
