#!/usr/bin/python3

import json

import matplotlib.patches
import matplotlib.pyplot as plt

plt.figure(figsize=(10, 10))

with open("output/phases.new.json") as f:
    dump = json.load(f)

phases = dump["phases"]
records = dump["records"]
time_base = records[0]["time"]
time_end = dump["end"]
real_start_time = {}
for record in records:
    if record["phase"] == "SETTING_UP_NETWORK":
        real_start_time[record["name"]] = record["time"]

for y, name in enumerate(sorted(set([record["name"] for record in records]), key=lambda name: real_start_time.get(name) or 0)):
    prev_time = None
    prev_phase = None
    xranges = []
    colors = []
    for record in records:
        if record["name"] != name:
            continue
        if prev_time is not None:
            xranges.append(((prev_time - time_base) / 60, (record["time"] - prev_time) / 60))
            colors.append("C" + str(phases.index(prev_phase)))
        prev_phase = record["phase"]
        prev_time = record["time"]
    if prev_phase != "DONE":
        xranges.append(((prev_time - time_base) / 60, (time_end - prev_time) / 60))
        colors.append("C" + str(phases.index(prev_phase)))
    plt.broken_barh(xranges, (y, 0.5), fc=colors)

plt.legend(handles=[
    matplotlib.patches.Patch(color=f'C{i}', label=phase)
    for i, phase in enumerate(phases)
])

plt.xlim((0, (time_end - time_base) / 60))
plt.savefig("phases.png")
