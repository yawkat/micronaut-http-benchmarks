# Micronaut HTTP server benchmarks

The build system is described in [this blog post](https://melix.github.io/blog/2023/gradle-synthetic-projects.html).

## Running

- The benchmarks use [h2load](https://nghttp2.org/documentation/h2load-howto.html) for load generation at the moment. You need to have it installed.
- The benchmarks only work with Graal EE at the moment, for PGO and G1GC. A Graal CE comparison is planned but will be difficult without resolution of a [gradle limitation](https://github.com/gradle/gradle/pull/18028). The Graal EE SDK must be on the PATH.
- The dependencies are currently hardcoded for x86_64 linux and probably won't work on other platforms.
- Run `./run-benchmarks.py --prepare-pgo`. This will build the native image with `--pgo-instrument`, apply a test load, and save the generated PGO data.
- Run `./run-benchmarks.py --verify-features`. This will build the normal native images (requires PGO data generated above) and verify that the features are toggled as expected (e.g. the epoll-on builds actually have working epoll).
- Run `./run-benchmarks.py` to run the actual benchmarks. This will take a while.
- Run `./plot.py` to plot the benchmark results.
