#!/usr/bin/python3
import json
import math
import typing

import matplotlib.pyplot as plt
import matplotlib.scale
import numpy as np


def percentile_transform(values):
    return -np.log10(1 - values)


def percentile_transform_reverse(values):
    return 1 - np.float_power(10, -values)


assert percentile_transform(0) == 0
assert math.isclose(percentile_transform(0.9), 1)
assert math.isclose(percentile_transform(0.99), 2)
assert math.isclose(percentile_transform(0.999), 3)
assert percentile_transform_reverse(0) == 0
assert math.isclose(percentile_transform_reverse(1), 0.9)
assert math.isclose(percentile_transform_reverse(2), 0.99)
assert math.isclose(percentile_transform_reverse(3), 0.999)


def generate_percentile_ticks(limit):
    i = 0
    while True:
        percentile = percentile_transform_reverse(i)
        yield percentile
        if percentile >= limit:
            break
        i += 1


def plot_histogram(ax: matplotlib.pyplot.Axes, benchmark_id: str, scale: float = 1, color: typing.Optional[str] = None, label: typing.Optional[str] = None):
    run_data = load_run(benchmark_id)
    selected_phase = find_main_phase(run_data)
    percentiles = [p for p in selected_phase["histogram"]["percentiles"] if p["percentile"] < 1]
    percentiles_x = [p["percentile"] for p in percentiles]
    ax.plot(
        percentiles_x,
        [p["to"] * scale for p in percentiles],
        color=color,
        label=label
    )
    return max(percentiles_x)


def find_main_phase(run_data):
    selected_phase = None
    for phase in run_data["stats"]:
        if phase["isWarmup"]:
            continue
        if selected_phase is not None:
            raise Exception("Multiple phases")
        selected_phase = phase
    return selected_phase


def load_run(benchmark_id: str):
    with open(f"output/{benchmark_id}/output.json") as f:
        return json.load(f)


def get_index_property(index_item: dict, property: typing.Sequence[str]):
    v = index_item
    for key in property:
        v = v[key]
    return v


def as_properties(index_item: dict, _parent_path=()) -> typing.Dict[typing.Sequence[str], typing.Any]:
    res = {}
    for k, v in index_item.items():
        path = (*_parent_path, k)
        if path == ("name",):
            continue

        if type(v) is dict:
            res.update(as_properties(v, _parent_path=path))
        else:
            res[path] = v
    return res


def matches(expected: typing.Dict[typing.Sequence[str], typing.Any], index_item: dict):
    for k, v in expected.items():
        if get_index_property(index_item, k) != v:
            return True
    return False


def has_error(benchmark_id: str):
    if find_main_phase(load_run(benchmark_id)) is None:
        print(f"Benchmark run {benchmark_id} failed")
        return True
    else:
        return False


def main():
    with open("output/index.json") as f:
        index = json.load(f)
    index = sorted(index, key=lambda i: i["name"])
    index = [i for i in index if not has_error(i["name"])]
    fig, ax = plt.subplots()
    discriminator_properties = [("load", "protocol")]
    filter_properties = {("type",): "mn-hotspot"}

    def get_discriminator_tuple(index_item: dict):
        return tuple(get_index_property(index_item, k) for k in discriminator_properties)

    def find_baseline(index_item: dict):
        baseline_filter_props = as_properties(index_item)
        for disc in discriminator_properties:
            del baseline_filter_props[disc]
        for candidate in index:
            if not matches(baseline_filter_props, candidate):
                return candidate

    colors_by_discriminator = {}
    max_percentile = 0
    for run in index:
        if matches(filter_properties, run):
            continue
        disc_tuple = get_discriminator_tuple(run)
        if disc_tuple not in colors_by_discriminator:
            colors_by_discriminator[disc_tuple] = "C" + str(len(colors_by_discriminator))
        baseline = find_baseline(run)
        print(f"Plotting {run['name']} with baseline {baseline['name']}")
        baseline_time = find_main_phase(load_run(baseline["name"]))["total"]["summary"]["percentileResponseTime"]["50.0"]
        max_percentile = max(max_percentile, plot_histogram(
            ax, run["name"],
            scale=1 / baseline_time,
            color=colors_by_discriminator[disc_tuple],
            label=" ".join(get_discriminator_tuple(run))
        ))

    percentile_ticks = list(generate_percentile_ticks(max_percentile))
    plt.xscale("function", functions=(percentile_transform, percentile_transform_reverse))
    plt.yscale("log")
    plt.axvline(0.5)
    plt.xlim(0, max_percentile)
    plt.xticks(percentile_ticks, [str(p) for p in percentile_ticks])
    plt.legend()
    plt.show()


main()
