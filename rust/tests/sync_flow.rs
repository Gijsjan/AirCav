use std::path::PathBuf;
use taskchampion::storage::AccessMode;
use taskchampion::{Operation, Operations, Replica, ServerConfig, Status, StorageConfig, Tag, Uuid};

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("tc-test-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn replica(dir: PathBuf) -> Replica {
    std::fs::create_dir_all(&dir).unwrap();
    let storage = StorageConfig::OnDisk {
        taskdb_dir: dir,
        create_if_missing: true,
        access_mode: AccessMode::ReadWrite,
    }
    .into_storage()
    .unwrap();
    Replica::new(storage)
}

fn add_task(replica: &mut Replica, description: &str, tags: &[&str]) -> Uuid {
    let mut ops = Operations::new();
    ops.push(Operation::UndoPoint);
    let uuid = Uuid::new_v4();
    let mut t = replica.create_task(uuid, &mut ops).unwrap();
    t.set_description(description.to_string(), &mut ops).unwrap();
    t.set_status(Status::Pending, &mut ops).unwrap();
    t.set_entry(Some(taskchampion::chrono::Utc::now()), &mut ops).unwrap();
    for tag in tags {
        t.add_tag(&Tag::try_from(*tag).unwrap(), &mut ops).unwrap();
    }
    replica.commit_operations(ops).unwrap();
    uuid
}

fn sync(replica: &mut Replica, server_dir: &std::path::Path) {
    let mut server = ServerConfig::Local {
        server_dir: server_dir.to_path_buf(),
    }
    .into_server()
    .unwrap();
    replica.sync(&mut server, true).unwrap();
}

fn descriptions(replica: &mut Replica) -> Vec<String> {
    replica
        .pending_tasks()
        .unwrap()
        .into_iter()
        .map(|t| t.get_description().to_string())
        .collect()
}

#[test]
fn phone_add_desktop_sees_after_sync_and_completion_round_trips() {
    let server_dir = temp_dir("server");
    let phone_dir = temp_dir("phone");
    let desktop_dir = temp_dir("desktop");

    // phone: add a task with a tag (same operations the Android JNI layer uses)
    let mut phone = replica(phone_dir.clone());
    let uuid = add_task(&mut phone, "buy milk", &["errands"]);
    sync(&mut phone, &server_dir);

    // desktop: fresh replica, sync, should see the task and its tag
    let mut desktop = replica(desktop_dir.clone());
    sync(&mut desktop, &server_dir);
    assert_eq!(descriptions(&mut desktop), vec!["buy milk".to_string()]);
    let task = desktop.get_task(uuid.clone()).unwrap().unwrap();
    assert!(task.get_tags().any(|t| t.to_string() == "errands"));

    // desktop: complete the task, sync
    let mut ops = Operations::new();
    ops.push(Operation::UndoPoint);
    let mut t = desktop.get_task(uuid.clone()).unwrap().unwrap();
    t.done(&mut ops).unwrap();
    desktop.commit_operations(ops).unwrap();
    sync(&mut desktop, &server_dir);

    // phone: sync, task is gone from pending
    sync(&mut phone, &server_dir);
    assert!(descriptions(&mut phone).is_empty());

    // deletion (status Deleted, as the app's "remove" does) also round-trips
    let uuid2 = add_task(&mut phone, "plotz", &[]);
    sync(&mut phone, &server_dir);
    sync(&mut desktop, &server_dir);
    let mut ops = Operations::new();
    ops.push(Operation::UndoPoint);
    let mut t = desktop.get_task(uuid2).unwrap().unwrap();
    t.set_status(Status::Deleted, &mut ops).unwrap();
    desktop.commit_operations(ops).unwrap();
    sync(&mut desktop, &server_dir);
    sync(&mut phone, &server_dir);
    assert!(descriptions(&mut phone).is_empty());

    std::fs::remove_dir_all(server_dir).ok();
    std::fs::remove_dir_all(phone_dir).ok();
    std::fs::remove_dir_all(desktop_dir).ok();
}
