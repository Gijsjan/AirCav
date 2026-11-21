//! A tiny Linux terminal harness for the same logic the Android JNI layer uses.
//!
//!   aircav-cli ~/myreplica add "buy milk +errands"
//!   aircav-cli ~/myreplica list
//!   aircav-cli ~/myreplica done <uuid>
//!   aircav-cli ~/myreplica remove <uuid>
//!   aircav-cli ~/myreplica sync http://localhost:8080 <client-id-uuid> <encryption-secret>

use std::path::PathBuf;
use std::str::FromStr;
use taskchampion::storage::AccessMode;
use taskchampion::{Operation, Operations, Replica, ServerConfig, Status, StorageConfig, Tag, Uuid};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!("usage: aircav-cli <dbdir> <add|list|done|remove|sync> [args...]");
        std::process::exit(2);
    }
    let dbdir = PathBuf::from(&args[0]);
    std::fs::create_dir_all(&dbdir).expect("cannot create db dir");
    let storage = StorageConfig::OnDisk {
        taskdb_dir: dbdir,
        create_if_missing: true,
        access_mode: AccessMode::ReadWrite,
    }
    .into_storage()
    .expect("cannot open storage");
    let mut replica = Replica::new(storage);

    match args[1].as_str() {
        "add" => {
            let text = args.get(2).expect("missing description");
            let mut ops = Operations::new();
            ops.push(Operation::UndoPoint);
            let mut t = replica
                .create_task(Uuid::new_v4(), &mut ops)
                .expect("create failed");
            let mut tags = Vec::new();
            let mut words = Vec::new();
            for word in text.split_whitespace() {
                match word.strip_prefix('+').or_else(|| word.strip_prefix('@')) {
                    Some(tag) if !tag.is_empty() => tags.push(Tag::from_str(tag).expect("invalid tag")),
                    _ => words.push(word),
                }
            }
            t.set_description(words.join(" "), &mut ops).expect("set_description failed");
            t.set_status(Status::Pending, &mut ops).expect("set_status failed");
            t.set_entry(Some(chrono::Utc::now()), &mut ops).expect("set_entry failed");
            for tag in &tags {
                t.add_tag(tag, &mut ops).expect("add_tag failed");
            }
            replica.commit_operations(ops).expect("commit failed");
            println!("added");
        }
        "list" => {
            for t in replica.pending_tasks().expect("list failed") {
                let tags: Vec<String> = t
                    .get_tags()
                    .filter(|g| !g.is_synthetic())
                    .map(|g| format!("+{g}"))
                    .collect();
                println!("{}  {}  {}", t.get_uuid(), t.get_description(), tags.join(" "));
            }
        }
        "done" | "remove" => {
            let uuid = Uuid::parse_str(args.get(2).expect("missing uuid")).expect("invalid uuid");
            let mut ops = Operations::new();
            ops.push(Operation::UndoPoint);
            if let Some(mut t) = replica.get_task(uuid).expect("get failed") {
                let status = if args[1] == "done" { Status::Completed } else { Status::Deleted };
                if args[1] == "done" {
                    t.done(&mut ops).expect("done failed");
                } else {
                    t.set_status(status, &mut ops).expect("set_status failed");
                }
                replica.commit_operations(ops).expect("commit failed");
                println!("ok");
            } else {
                println!("not found");
            }
        }
        "sync" => {
            let url = args.get(2).expect("missing url");
            let client_id = Uuid::parse_str(args.get(3).expect("missing client id")).expect("invalid client id");
            let secret = args.get(4).expect("missing secret");
            let mut server = ServerConfig::Remote {
                url: url.clone(),
                client_id,
                encryption_secret: secret.as_bytes().to_vec(),
            }
            .into_server()
            .expect("invalid server config");
            replica.sync(&mut server, true).expect("sync failed");
            println!("synced");
        }
        other => {
            eprintln!("unknown command: {other}");
            std::process::exit(2);
        }
    }
}
