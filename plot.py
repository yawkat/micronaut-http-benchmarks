#!/usr/bin/python3
import collections
import json

import matplotlib.pyplot as plt

print("Loading results")
with open("results.json") as f:
    results = json.load(f)

fig, ax = plt.subplots()
ax.semilogx()

RunParameters = collections.namedtuple("RunParameters", results[0]["parameters"].keys())

color_by_haystack_size = {6: '', 1000: 'r', 100_000: 'g'}
color_by_haystack_size_rgba = {
    (6, 6): 'lightgreen',
    (6, 1000): 'limegreen',
    (6, 100_000): 'darkgreen',
    (1000, 6): 'lightblue',
    (100_000, 6): 'pink'}


# https://stackoverflow.com/a/1151686
class HashableParameters(dict):
    def __key(self):
        return tuple((k,self[k]) for k in sorted(self))

    def __hash__(self):
        return hash(self.__key())

    def __eq__(self, other):
        return self.__key() == other.__key()


def group_parameter(parameter: dict):
    return HashableParameters({k: v for k, v in parameter.items() if k != "haystack_size"})


grouped_parameters = list(collections.OrderedDict.fromkeys([group_parameter(row["parameters"]) for row in results]))

if "ok_times" in results[0]:
    # gatling results
    ax.boxplot([row["ok_times"] for row in results])
if "time_to_1st_byte" in results[0]:
    # h2load results
    for j, (haystack_size, color) in enumerate(color_by_haystack_size.items()):
        ax.errorbar(
            y=[i + j * 0.1 for i in range(len(grouped_parameters))],
            x=[row["time_to_1st_byte"]["mean"] for row in results if tuple(row["parameters"]["haystack_size"]) == haystack_size],
            xerr=[row["time_to_1st_byte"]["sd"] for row in results if tuple(row["parameters"]["haystack_size"]) == haystack_size],
            xlolims=1,
            linewidth=0,
            fmt=color + ".",
            label=f"haystack_size={haystack_size}"
        )
    plt.xlim(left=1_000_000)
    plt.xticks([1_000_000, 1_000_000_0, 1_000_000_00, 1_000_000_000])
    ax.set_xticklabels(["1ms", "10ms", "100ms", "1s"])
if "times_for_requests" in results[0]:
    # h2load request timings (microseconds)
    for j, (haystack_size, color) in enumerate(color_by_haystack_size_rgba.items()):
        print(f"Plotting {j}")
        bplot = ax.boxplot(
            x=[row["times_for_requests"] for row in results if tuple(row["parameters"]["haystack_size"]) == haystack_size],
            positions=[i + j * 0.1 for i in range(len(grouped_parameters))],
            vert=False,
            patch_artist=True,
            widths=0.08,
            showfliers=False
        )
        for patch in bplot['boxes']:
            patch.set_facecolor(color)
    plt.xlim(left=1_000)
    plt.xticks([1_000, 1_000_0, 1_000_00, 1_000_000, 1_000_000_0])
    ax.set_xticklabels(["1µs", "10µs", "100µs", "1ms", "10ms"])


def parameter_label(parameters: RunParameters):
    s = []
    for k, v in parameters.items():
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
ax.legend()
plt.grid(which="both")
plt.tight_layout(pad=1)

plt.show()
