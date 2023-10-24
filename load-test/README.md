
# Micromon Load Tester

A website load tester built on the very fast [goose](https://crates.io/crates/goose) crate.


## Build

First install the [goose prerequisites](https://book.goose.rs/requirements.html),
which are basically just a modern Rust development environment.

Then build the load-tester with:
```shell
cargo build --release
```
The binary should appear at `target/release/load-test`.


## Run

Copy the binary to a server you'd like to run the tests *from*, ideally
not the same server you're running the tests *on*.

Then run binary with one scenario and one load, eg:
```shell
load-test http://theserver.app index warmup
```

To see all scenarios and loads, run the help message:
```shell
load-test --help
```
