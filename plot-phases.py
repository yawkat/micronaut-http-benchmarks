#!/usr/bin/python3

import json

import matplotlib.colors
import matplotlib.patches
import matplotlib.pyplot as plt

plt.figure(figsize=(10, 12))

with open("output/phases.new.json") as f:
    dump = json.load(f)

phases = dump["phases"][:-2]
records = dump["records"]
time_base = records[0]["time"]
time_end = dump["end"]
real_start_time = {}
for record in records:
    if record["phase"] == "SETTING_UP_NETWORK":
        real_start_time[record["name"]] = record["time"]


def color_for_record(rec):
    if rec["phase"] not in phases:
        return "black"
    percent = rec["phasePercentage"]
    return matplotlib.colors.to_rgba("C" + str(phases.index(rec["phase"])), 1 - percent)


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
            colors.append(color_for_record(prev_phase))
        prev_phase = record
        prev_time = record["time"]
    if prev_phase["phase"] != "DONE":
        xranges.append(((prev_time - time_base) / 60, (time_end - prev_time) / 60))
        colors.append(color_for_record(prev_phase))
    plt.broken_barh(xranges, (y, 0.5), fc=colors)

plt.legend(handles=[
    matplotlib.patches.Patch(color=f'C{i}', label=phase)
    for i, phase in enumerate(phases)
], loc='upper left')

plt.xlim((0, (time_end - time_base) / 60))
plt.tight_layout()
plt.savefig("phases.png")
