#!/usr/bin/python3
import json

import matplotlib.pyplot as plt

with open("results.json") as f:
    results = json.load(f)

fig, ax = plt.subplots()
ax.semilogy()
if "ok_times" in results[0]:
    # gatling results
    ax.boxplot([row["ok_times"] for row in results])
if "time_to_1st_byte" in results[0]:
    # h2load results
    ax.bar(
        x=[i for i in range(len(results))],
        height=[row["time_to_1st_byte"]["mean"] for row in results],
        #yerr=[row["time_to_1st_byte"]["sd"] for row in results],
    )
    print([row["time_to_1st_byte"]["mean"] for row in results])
    plt.ylim(bottom=1000000)


def parameter_label(parameters):
    s = []
    for k, v in parameters.items():
        if type(v) is bool:
            if v:
                s.append(k)
        else:
            s.append(v)
    return " ".join(s)


plt.xticks(list(range(len(results))), rotation=90)
ax.set_xticklabels([parameter_label(row["parameters"]) for row in results])
plt.tight_layout()

plt.show()
