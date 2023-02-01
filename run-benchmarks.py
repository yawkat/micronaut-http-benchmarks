#!/usr/bin/python3
import collections
import subprocess
import os
import csv
import json
import itertools
import time

import requests

pgo_data_dir = "pgo-data"
DEFAULT_DIMENSIONS = {
    "protocol": ['http', 'https1', 'https2'],
    "native": [False, True],
    "tcnative": [False, True],
    "epoll": [False, True],
    "json": ["jackson", "serde"],
}

RunParameters = collections.namedtuple("RunParameters", DEFAULT_DIMENSIONS.keys())
GatlingResult = collections.namedtuple("GatlingResult", ("ko", "ok_times"))

BOLD = '\033[1m'
RED = '\033[91m'
DEFAULT = '\033[0m'


def build_parameters(dimensions):
    return [
        RunParameters(*combination)
        for combination in itertools.product(*dimensions.values())
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
        if k != "native" and k != "protocol"
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
            results[params] = run_gatling(params.protocol, duration_per_test)
        finally:
            server_proc.terminate()
            server_proc.wait()
    with open("results.json", "w") as f:
        json.dump([
            {
                "parameters": param._asdict(),
                "ko": result.ko,
                "ok_times": result.ok_times
            }
            for param, result in results.items()
        ], f)
    for param, (ko, ok_times) in results.items():
        ok_times.sort()
        print(
            param,
            len(ok_times),
            ko,
            ok_times[int(len(ok_times) * 0.5)],
            ok_times[int(len(ok_times) * 0.95)],
            max(ok_times),
            sum(ok_times) / len(ok_times)
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
            for protocol in DEFAULT_DIMENSIONS["protocol"]:
                run_gatling(protocol, duration_per_test)
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
        dimensions["tcnative"] = [False]
        dimensions["epoll"] = [False]
        dimensions["native"] = [False]
        dimensions["json"] = ["jackson"]
        dimensions["protocol"] = ["http"]
        duration_per_test = 10
    if args.prepare_pgo:
        duration_per_test = 2
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
