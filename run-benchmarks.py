#!/usr/bin/python3

import subprocess

protocols = ['http', 'https1', 'https2']
test_case_modules = ["test-case", "test-case-tcnative"]

BOLD = '\033[1m'
DEFAULT = '\033[0m'


def run(cmd):
    print(BOLD + cmd + DEFAULT)
    subprocess.run(cmd, shell=True, check=True)


def start_server(module: str, native: bool, args: str):
    if native:
        run(f"{module}/build/native/nativeCompile/{module} {args}")
    else:
        run(f"java -Xmx1G -jar {module}/build/libs/{module}-runner.jar {args}")


def main():
    run("./gradlew nativeCompile shadowJar")
    for module in test_case_modules:
        for native in [False, True]:
            if native:
                server_cmd = [f"{module}/build/native/nativeCompile/{module}"]
            else:
                server_cmd = ["java", "-Xmx1G", "-jar", f"{module}/build/libs/{module}-all.jar"]
            print(BOLD + " ".join(server_cmd) + DEFAULT)
            server_proc = subprocess.Popen(server_cmd)
            try:
                run("./gradlew :load-generator-gatling:gatlingRun")
            finally:
                server_proc.terminate()
                server_proc.wait()


if __name__ == '__main__':
    main()
