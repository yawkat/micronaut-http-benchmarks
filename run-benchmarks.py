#!/usr/bin/python3
import collections
import csv
import itertools
import json
import os
import random
import string
import subprocess
import time

import requests

pgo_data_dir = "pgo-data"
DEFAULT_DIMENSIONS = {
    "protocol": ['http', 'https1', 'https2'],
    "native": [False, True],
    "tcnative": [False, True],
    "epoll": [False, True],
    "json": ["jackson"], # no serde for now
    "micronaut": ["3.8"],
    "java": ["17"],
    "haystack_size": [6, 1000, 100_000]
}

RunParameters = collections.namedtuple("RunParameters", DEFAULT_DIMENSIONS.keys())
GatlingResult = collections.namedtuple("GatlingResult", ("ko", "ok_times"))

BOLD = '\033[1m'
RED = '\033[91m'
DEFAULT = '\033[0m'


def build_parameters(dimensions):
    combs = [
        RunParameters(*combination)
        for combination in itertools.product(*dimensions.values())
    ]
    return [
        c for c in combs
        # disable epoll/tcnative for native image, because they don't work anyway.
        if not c.native or (not c.tcnative and not c.epoll)
    ]


def run(cmd):
    print(BOLD + cmd + DEFAULT)
    subprocess.run(cmd, shell=True, check=True)


def run_gatling(protocol: str, duration_per_test: int):
    report_dir = "load-generator-gatling/build/reports/gatling"
    os.makedirs(report_dir, exist_ok=True)
    old_reports = set(os.listdir(report_dir))
    run(f"./gradlew :load-generator-gatling:gatlingRun -Dprotocol={protocol} -Dduration={duration_per_test}")
    new_reports = set(os.listdir(report_dir)) - old_reports
    if len(new_reports) != 1:
        raise Exception("Could not find gatling report")
    simulation_log = os.path.join(report_dir, list(new_reports)[0], "simulation.log")
    ok_times = []
    ko = 0
    with open(simulation_log) as f:
        reader = csv.reader(f, delimiter='\t')
        for row in reader:
            if row[0] == "REQUEST":
                time = int(row[4]) - int(row[3])
                ok = row[5] == "OK"
                if ok:
                    ok_times.append(time)
                else:
                    ko += 1
    return GatlingResult(
        ko=ko,
        ok_times=ok_times
    )


def parse_h2load_time(time: str) -> float:
    if time.endswith("ns"):
        return float(time[:-2].lstrip())
    if time.endswith("µs") or time.endswith("us"):
        return float(time[:-2].lstrip()) * 1000
    if time.endswith("ms"):
        return float(time[:-2].lstrip()) * 1000_000
    if time.endswith("s"):
        return float(time[:-1].lstrip()) * 1000_000_000
    raise ValueError(f"Cannot parse time: {time}")


SummaryStats = collections.namedtuple("SummaryStats", ("min", "max", "mean", "sd", "dsd"))


def parse_h2load_metric(line: str):
    line = line[len("time for request:    "):]  # remove caption
    parts = line.strip().split()
    return SummaryStats(
        min=parse_h2load_time(parts[0]),
        max=parse_h2load_time(parts[1]),
        mean=parse_h2load_time(parts[2]),
        sd=parse_h2load_time(parts[3]),
        dsd=float(parts[4].lstrip().removesuffix("%")) / 100,
    )


H2loadResult = collections.namedtuple("H2loadResult", ("time_for_request", "time_for_connect", "time_to_1st_byte"))


def random_string(length):
    return ''.join(random.choices(string.ascii_lowercase, k=length))


def run_h2load(protocol: str, duration_per_test: int, conn_per_s: int, body_size_parameter: int):
    total_conns = conn_per_s * duration_per_test
    # could use a temp file, but this allows running the tests outside of this script
    body_file_name = f"test-body-{body_size_parameter}.json"
    with open(body_file_name, mode="w") as body_file:
        haystack = [random_string(body_size_parameter) for _ in range(6)]
        offset = random.randrange(0, body_size_parameter - 3)
        json.dump({
            "haystack": haystack,
            "needle": random.choice(haystack)[offset:offset + 3]
        }, body_file)

    cmd = [
        "h2load",
        "--no-tls-proto=http/1.1",  # for http, always use http/1.1
        "--npn-list=" + ('http/1.1' if protocol == 'https1' else 'h2'), # for https, set the right protocol
        "-d", body_file.name,
        "-H", "Content-Type: application/json",
        f"--clients={total_conns}",
        f"--requests={total_conns}",
        f"--rate={conn_per_s}",
        "--threads=8",
        ("http://localhost:8080" if protocol == "http" else "https://localhost:8443") + "/search/find"
    ]
    print(BOLD + " ".join(cmd) + DEFAULT)
    output = subprocess.run(cmd, check=True, stdout=subprocess.PIPE).stdout.decode("utf-8")
    print(output)
    tfr = None
    tfc = None
    ttfb = None
    for line in output.split("\n"):
        if line.startswith("time for request:"):
            tfr = parse_h2load_metric(line)
        elif line.startswith("time for connect:"):
            tfc = parse_h2load_metric(line)
        elif line.startswith("time to 1st byte:"):
            ttfb = parse_h2load_metric(line)
    return H2loadResult(tfr, tfc, ttfb)


def build_server_command(params: RunParameters):
    combination_string = build_combination_string(params)
    if params.native:
        return [f"build/native-images/{combination_string}"]
    else:
        return ["java", "-Xmx1G", "-jar", f"build/libs/{combination_string}-all.jar"]


def build_combination_string(params):
    combination_string = "-".join([
        k + "-" + (params[i] if type(params[i]) is str else ("on" if params[i] else "off"))
        for i, k in enumerate(DEFAULT_DIMENSIONS.keys())
        if k not in ("native", "protocol", "haystack_size")
    ])
    return combination_string


def compile_standard_artifacts():
    run(f"./gradlew shadowJar nativeCompile -DpgoDataDirectory={os.path.abspath(pgo_data_dir)}")


def benchmark(duration_per_test, parameters):
    compile_standard_artifacts()
    results = {}
    for i, params in enumerate(parameters):
        print(BOLD + f"Running test {i + 1}/{len(parameters)}")
        server_cmd = build_server_command(params)
        print(BOLD + " ".join(server_cmd) + DEFAULT)
        server_proc = subprocess.Popen(server_cmd)
        try:
            time.sleep(1 if params.native else 4)  # wait for startup
            results[params] = run_h2load(params.protocol, duration_per_test, 500, params.haystack_size)
        finally:
            server_proc.terminate()
            server_proc.wait()
    with open("results.json", "w") as f:
        json.dump([
            {
                "parameters": param._asdict(),
                **{
                    k: v._asdict() if type(v) is SummaryStats else v
                    for k, v
                    in result._asdict().items()
                }
            }
            for param, result in results.items()
        ], f)
    for param, result in results.items():
        if type(result) is GatlingResult:
            result.ok_times.sort()
            print(
                param,
                len(result.ok_times),
                result.ko,
                result.ok_times[int(len(result.ok_times) * 0.5)],
                result.ok_times[int(len(result.ok_times) * 0.95)],
                max(result.ok_times),
                sum(result.ok_times) / len(result.ok_times)
            )
        elif type(result) is H2loadResult:
            print(
                param,
                f"{result.time_to_1st_byte.mean / 1000000}ms"
            )


def prepare_pgo(duration_per_test, parameters):
    run("./gradlew nativeCompile -DpgoInstrument")
    os.makedirs(pgo_data_dir, exist_ok=True)
    to_run = [
        p
        for p in parameters
        if p.protocol == DEFAULT_DIMENSIONS["protocol"][0] # one profile run for all protocols
        if p.native
    ]
    for i, params in enumerate(to_run):
        print(BOLD + f"Building profile for {i + 1}/{len(to_run)}")
        server_cmd = build_server_command(params)
        print(BOLD + " ".join(server_cmd) + DEFAULT)
        server_proc = subprocess.Popen(server_cmd)
        try:
            time.sleep(1)  # wait for startup
            for protocol in DEFAULT_DIMENSIONS["protocol"]:
                for haystack_size in DEFAULT_DIMENSIONS["haystack_size"]:
                    run_h2load(protocol, duration_per_test, 10, haystack_size)
        finally:
            server_proc.terminate()
            server_proc.wait()
        os.rename("default.iprof", os.path.join(pgo_data_dir, build_combination_string(params)))


def verify_features(parameters):
    compile_standard_artifacts()
    to_run = [
        p
        for p in parameters
        if p.protocol == DEFAULT_DIMENSIONS["protocol"][0] # one profile run for all protocols
    ]
    results = {}
    for i, params in enumerate(to_run):
        print(BOLD + f"Checking features {i + 1}/{len(to_run)}")
        server_cmd = build_server_command(params)
        print(BOLD + " ".join(server_cmd) + DEFAULT)
        server_proc = subprocess.Popen(server_cmd)
        try:
            time.sleep(1 if params.native else 2)  # wait for startup
            results[params] = requests.get("http://localhost:8080/status").json()
        finally:
            server_proc.terminate()
            server_proc.wait()
    for param, status in results.items():
        line = [str(param)]
        channel_implementation: str = status["serverSocketChannelImplementation"]
        ssl_provider: str = status["sslProvider"]
        json_implementation: str = status["jsonMapperImplementation"]
        if param.tcnative and ssl_provider == "JDK":
            line.append(RED + ssl_provider + DEFAULT)
        else:
            line.append(ssl_provider)
        if param.epoll and channel_implementation != "io.netty.channel.epoll.EpollServerSocketChannel":
            line.append(RED + channel_implementation + DEFAULT)
        else:
            line.append(channel_implementation)
        if param.json == "serde" and not json_implementation.startswith("io.micronaut.serde"):
            line.append(RED + json_implementation + DEFAULT)
        else:
            line.append(json_implementation)
        print(" ".join(line))


def main():
    import argparse
    parser = argparse.ArgumentParser(prog="run-benchmarks.py")
    parser.add_argument("--duration-per-test", help="Duration for each gatling test", type=int)
    parser.add_argument("--test-run", help="Fast test run with reduced test matrix", action='store_true')
    parser.add_argument("--prepare-pgo", help="Prepare profile-guided optimization", action='store_true')
    parser.add_argument("--verify-features", help="Verify that the right features are supported by the artifacts", action='store_true')
    args = parser.parse_args()

    duration_per_test = 30

    dimensions = dict(DEFAULT_DIMENSIONS)
    if args.test_run:
        dimensions["tcnative"] = [True]
        dimensions["epoll"] = [True]
        dimensions["native"] = [False]
        dimensions["json"] = ["jackson"]
        dimensions["protocol"] = [DEFAULT_DIMENSIONS["protocol"][0]]
        duration_per_test = 10
    if args.duration_per_test is not None:
        duration_per_test = args.duration_per_test

    parameters = build_parameters(dimensions)
    if args.prepare_pgo:
        prepare_pgo(duration_per_test, parameters)
    elif args.verify_features:
        verify_features(parameters)
    else:
        benchmark(duration_per_test, parameters)


if __name__ == '__main__':
    main()
