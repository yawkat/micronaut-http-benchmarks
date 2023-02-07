#!/usr/bin/python3
import collections
import json

import matplotlib.pyplot as plt

with open("results.json") as f:
    results = json.load(f)

fig, ax = plt.subplots()
ax.semilogx()

RunParameters = collections.namedtuple("RunParameters", results[0]["parameters"].keys())

color_by_haystack_size = {6: '', 1000: 'r', 100_000: 'g'}


def group_parameter(parameter: dict) -> RunParameters:
    return RunParameters(**parameter)._replace(haystack_size=0)


grouped_parameters = list(collections.OrderedDict.fromkeys([group_parameter(row["parameters"]) for row in results]))

if "ok_times" in results[0]:
    # gatling results
    ax.boxplot([row["ok_times"] for row in results])
if "time_to_1st_byte" in results[0]:
    # h2load results
    for j, (haystack_size, color) in enumerate(color_by_haystack_size.items()):
        ax.errorbar(
            y=[i + j * 0.1 for i in range(len(grouped_parameters))],
            x=[row["time_to_1st_byte"]["mean"] for row in results if row["parameters"]["haystack_size"] == haystack_size],
            xerr=[row["time_to_1st_byte"]["sd"] for row in results if row["parameters"]["haystack_size"] == haystack_size],
            xlolims=1,
            linewidth=0,
            fmt=color + ".",
            label=f"haystack_size={haystack_size}"
        )
    print([row["time_to_1st_byte"]["mean"] for row in results])
    plt.xlim(left=1_000_000)


def parameter_label(parameters: RunParameters):
    s = []
    for k, v in parameters._asdict().items():
        if k == "haystack_size":
            continue
        if type(v) is bool:
            if v:
                s.append(k)
        else:
            s.append(v)
    return " ".join(s)


plt.yticks(list(range(len(grouped_parameters))))
ax.set_yticklabels([parameter_label(p) for p in grouped_parameters])
plt.xticks([1_000_000, 1_000_000_0, 1_000_000_00, 1_000_000_000])
ax.set_xticklabels(["1ms", "10ms", "100ms", "1s"])
ax.legend()
plt.grid(which="both")
plt.tight_layout(pad=1)

plt.show()
