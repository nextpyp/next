
# Host Processor

This program enables the nextPYP website to interact with the
host operating system outside of the container.


## Building

### Prerequisites

1. Install Rust \
   https://www.rust-lang.org/tools/install


### Cargo

`cargo` is the build tool for Rust programs.
To build the launcher, run the following command:

```shell
RUSTFLAGS="-C target-feature=+crt-static" cargo build --release --target x86_64-unknown-linux-musl
```

The executable will appear at `target/x86_64-unknown-linux-musl/release/launcher`.

**NOTE**: We have to put the `rustc` compile flags in the environment variable
for now (because `.cargo/config.toml` only applies to the current crate,
not any dependency crates), but we could someday move them into `Cargo.toml`
after `cargo` moves the `profile-rustflags` feature to the stable branch.
See https://github.com/rust-lang/cargo/issues/10271 for the tracking issue.
After that, the build command could just be `cargo build --release`.

**NOTE**: The explicit compilation target (eg `x86_64-unknown-linux-musl`)
is needed to work around an issue where we want to compile the final binary
with purely static linking, but we want to compile the proc macro
(used by the `thiserror` crate) on the host with regular settings.
Specifying a target explicitly puts `cargo`/`rustc` into cross-compilation
mode with handles compiling proc macros correctly. See the Rust GitHub issue
for more details:
https://github.com/rust-lang/rust/issues/78210


## Testing

To run the test suite, run the following command:

```shell
cargo test -- --test-threads=1
```
