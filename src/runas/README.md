
# Runas

This program enables the nextPYP website to run commands on behalf of another
user by using the setuid bit on the executable file.


## Building

### Prerequisites

1. Install Rust \
   https://www.rust-lang.org/tools/install


### Cargo

`cargo` is the build tool for Rust programs.
To build this program, run the following command in this folder:

```shell
cargo build --release
```

The executable will appear at `target/release/runas`.

**NOTE**: While this will build a working executable for your system,
building an executable that will work on other systems takes some extra work.
The strategy here is to dynamically link against the oldest GNU libc
we can find. The parent `next` project has a CentOS 7 container
for building Rust applications against a very old GNU libc.
Run the Gradle task `vmBuildRunas` to build inside the
container (inside the dev VM) and then look for the built executable
in the `run` folder. That executable should run on any relatively
modern linux distribution.


## Testing

This project has no tests to run. Yet?
