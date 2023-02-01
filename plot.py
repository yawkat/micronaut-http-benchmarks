#!/usr/bin/python3
import json

import matplotlib.pyplot as plt

with open("results.json") as f:
    results = json.load(f)

fig, ax = plt.subplots()
ax.semilogy()
ax.boxplot([row["ok_times"] for row in results])


def parameter_label(parameters):
    s = []
    for k, v in parameters.items():
        if type(v) is bool:
            if v:
                s.append(k)
        else:
            s.append(v)
    return " ".join(s)


plt.xticks(rotation=90)
ax.set_xticklabels([parameter_label(row["parameters"]) for row in results])
plt.tight_layout()

plt.show()
