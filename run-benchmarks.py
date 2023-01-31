#!/usr/bin/python3
import collections
import subprocess
import os
import csv

protocols = ['http', 'https1', 'https2']
test_case_modules = ["default", "tcnative"]
native_options = [False, True]

test = False
if test:
    native_options = [False]
    protocols = ['https1']

BOLD = '\033[1m'
DEFAULT = '\033[0m'


def run(cmd):
    print(BOLD + cmd + DEFAULT)
    subprocess.run(cmd, shell=True, check=True)


GatlingResult = collections.namedtuple("GatlingResult", ("max", "mean", "avg", "v95", "ok", "ko"))


def run_gatling(protocol):
    report_dir = "load-generator-gatling/build/reports/gatling"
    old_reports = set(os.listdir(report_dir))
    run(f"./gradlew :load-generator-gatling:gatlingRun -Dprotocol={protocol}")
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
    ok_times.sort()
    return GatlingResult(
        ko=ko,
        ok=len(ok_times),
        max=max(ok_times),
        mean=ok_times[int(len(ok_times) * 0.5)],
        v95=ok_times[int(len(ok_times) * 0.95)],
        avg=sum(ok_times) / len(ok_times),
    )


RunParameters = collections.namedtuple("RunParameters", ("module", "native", "protocol"))


def main():
    run("./gradlew allVariants")
    results = {}
    for module in test_case_modules:
        for native in native_options:
            if native:
                server_cmd = [f"test-case/build/native/native{module.capitalize() if module != 'default' else ''}Compile/test-case"]
            else:
                server_cmd = ["java", "-Xmx1G", "-jar", f"test-case/build/libs/test-case-{module if module != 'default' else 'all'}.jar"]
            print(BOLD + " ".join(server_cmd) + DEFAULT)
            server_proc = subprocess.Popen(server_cmd)
            try:
                for protocol in protocols:
                    results[RunParameters(module, native, protocol)] = run_gatling(protocol)
            finally:
                server_proc.terminate()
                server_proc.wait()
    for param, result in results.items():
        print(param.module, param.native, param.protocol, result.ok, result.ko, result.mean, result.v95, result.max, result.avg)


if __name__ == '__main__':
    main()
