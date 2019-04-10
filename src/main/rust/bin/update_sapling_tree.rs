extern crate ff;
extern crate futures;
extern crate grpc;
extern crate hex;
extern crate pairing;
extern crate protobuf;
extern crate zcash_client_backend;
extern crate zcash_primitives;

mod service;
mod service_grpc;

use ff::{PrimeField, PrimeFieldRepr};
use futures::Stream;
use grpc::ClientStubExt;
use pairing::bls12_381::{Fr, FrRepr};
use zcash_client_backend::proto::compact_formats;
use zcash_primitives::{merkle_tree::CommitmentTree, sapling::Node};

use service_grpc::CompactTxStreamer;

const LIGHTWALLETD_HOST: &str = "lightwalletd.z.cash";
const LIGHTWALLETD_PORT: u16 = 9067;
const BATCH_SIZE: u64 = 10_000;

fn print_sapling_tree(height: u64, time: u32, tree: CommitmentTree<Node>) {
    let mut tree_bytes = vec![];
    tree.write(&mut tree_bytes).unwrap();
    println!("{{");
    println!("  \"height\": {},", height);
    println!("  \"time\": {},", time);
    println!("  \"tree\": \"{}\",", hex::encode(tree_bytes));
    println!("}}");
}

fn main() {
    // For now, start from Sapling activation height
    let mut start_height = 280000;
    let mut tree = CommitmentTree::new();

    let client_conf = Default::default();
    let client = service_grpc::CompactTxStreamerClient::new_plain(
        LIGHTWALLETD_HOST,
        LIGHTWALLETD_PORT,
        client_conf,
    )
    .unwrap();

    loop {
        // Get the latest height
        let latest_height = client
            .get_latest_block(grpc::RequestOptions::new(), service::ChainSpec::new())
            .wait_drop_metadata()
            .unwrap()
            .height;
        let end_height = if latest_height - start_height < BATCH_SIZE {
            latest_height
        } else {
            start_height + BATCH_SIZE - 1
        };

        // Request the next batch of blocks
        println!("Fetching blocks {}..{}", start_height, end_height);
        let mut start = service::BlockID::new();
        start.set_height(start_height);
        let mut end = service::BlockID::new();
        end.set_height(end_height);
        let mut range = service::BlockRange::new();
        range.set_start(start);
        range.set_end(end);
        let blocks = client
            .get_block_range(grpc::RequestOptions::new(), range)
            .drop_metadata()
            .wait();

        let mut end_time = 0;
        let mut parsed = 0;
        for block in blocks {
            let block = block.unwrap();
            end_time = block.time;
            for tx in block.vtx.iter() {
                for output in tx.outputs.iter() {
                    // Append commitment to tree
                    let mut repr = FrRepr::default();
                    repr.read_le(&output.cmu[..]).unwrap();
                    let cmu = Fr::from_repr(repr).unwrap();
                    let node = Node::new(cmu.into_repr());
                    tree.append(node).unwrap();
                }
            }
            parsed += 1
        }
        println!("Parsed {} blocks", parsed);

        if end_height == latest_height {
            print_sapling_tree(end_height, end_time, tree);
            break;
        } else {
            start_height = end_height + 1
        }
    }
}
