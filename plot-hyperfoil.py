#!/usr/bin/python3
import json
import math
import typing

import matplotlib.patches
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


def load_histogram(benchmark_id: str, phase: str):
    run_data = load_run(benchmark_id)
    selected_phase = find_phase(run_data, phase)
    if selected_phase is None:
        return None
    return selected_phase["histogram"]["percentiles"]


def plot_histogram(ax: matplotlib.pyplot.Axes, percentiles, scale: float = 1, color: typing.Optional[str] = None, label: typing.Optional[str] = None):
    percentiles = [p for p in percentiles if p["percentile"] != 1]
    percentiles_x = [p["percentile"] for p in percentiles]
    ax.plot(
        percentiles_x,
        [p["to"] * scale for p in percentiles],
        color=color,
        label=label
    )


def find_phase(run_data, name):
    selected_phase = None
    for phase in run_data["stats"]:
        if phase["name"] != name:
            continue
        selected_phase = phase
    return selected_phase


run_cache = {}


def load_run(benchmark_id: str):
    if benchmark_id not in run_cache:
        with open(f"output/{benchmark_id}/output.json") as f:
            run_cache[benchmark_id] = json.load(f)
    return run_cache[benchmark_id]


def get_index_property(index_item: dict, property: typing.Sequence[str]):
    v = index_item
    for key in property:
        if key not in v:
            return None
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
    if find_phase(load_run(benchmark_id), "main/0") is None:
        print(f"Benchmark run {benchmark_id} failed")
        return True
    else:
        return False


def combine_histograms(histograms):
    bucket_to = list(sorted([
        bucket["to"]
        for histogram in histograms
        for bucket in histogram
    ]))
    bucket_to.insert(0, 0)
    bucket_count = [0 for _ in bucket_to]
    total_count = 0

    for histogram in histograms:
        for in_bucket in histogram:
            from_bucket = bucket_to.index(in_bucket["from"])
            to_bucket = bucket_to.index(in_bucket["to"])
            total_count += in_bucket["count"]
            for i in range(from_bucket + 1, to_bucket + 1):
                bucket_count[i] += in_bucket["count"] * (bucket_to[i] - bucket_to[i - 1]) / (in_bucket["to"] - in_bucket["from"])

    out = []
    assert bucket_count[0] == 0
    count_so_far = 0
    for i in range(1, len(bucket_to)):
        count_so_far += bucket_count[i]
        out.append({
            "from": bucket_to[i - 1],
            "to": bucket_to[i],
            "percentile": count_so_far / total_count,
            "count": bucket_count[i],
            "totalCount": count_so_far
        })
    return out



# matches application.toml hyperfoil.ops
OPS = [5, 10, 15, 25, 50, 75, 100, 200, 400, 800, 1000, 2000, 4000, 8000, 16000]


def main():
    with open("output/index.json") as f:
        index = json.load(f)
    index = sorted(index, key=lambda i: i["name"])
    index = [i for i in index if not has_error(i["name"])]
    rows = 4
    cols = int(np.ceil(len(OPS) / rows))
    fig, axs = plt.subplots(rows, cols)
    discriminator_properties = [("type",), ("parameters", "micronaut")]
    filter_properties = {
        #("type",): "mn-hotspot"
    }

    def get_discriminator_tuple(index_item: dict):
        return tuple(get_index_property(index_item, k) for k in discriminator_properties)

    def find_baseline(index_item: dict):
        baseline_filter_props = as_properties(index_item)
        for disc in discriminator_properties:
            if disc in baseline_filter_props:
                del baseline_filter_props[disc]
        for candidate in index:
            if not matches(baseline_filter_props, candidate):
                return candidate

    max_percentile = 0
    max_time = 0
    min_time = None
    discriminated = []
    for run in index:
        if matches(filter_properties, run):
            continue
        run_data = load_run(run["name"])
        for phase in run_data["stats"]:
            for percentile in phase["histogram"]["percentiles"]:
                if percentile["percentile"] != 1.0:
                    max_percentile = max(max_percentile, percentile["percentile"])
                max_time = max(max_time, percentile["to"])
                if min_time is None:
                    min_time = percentile["to"]
                else:
                    min_time = min(min_time, percentile["to"])
        discriminator_tuple = get_discriminator_tuple(run)
        if discriminator_tuple not in discriminated:
            discriminated.append(discriminator_tuple)
    max_time = 10**6.5
    max_percentile = 0.999
    print(min_time, max_time)
    colors_by_discriminator = {d: f'C{i}' for i, d in enumerate(discriminated)}

    for phase_i, ops in enumerate(OPS):
        phase = f"main/{phase_i}"
        ax = axs[phase_i // len(axs[0])][phase_i % len(axs[0])]

        any_shown = False
        for disc_tuple in discriminated:
            runs_for_discriminator = [run for run in index if not matches(filter_properties, run) and get_discriminator_tuple(run) == disc_tuple]
            disc_histograms = []
            for run in runs_for_discriminator:
                print(f"Plotting {phase} {run['name']}")
                histogram = load_histogram(run["name"], phase)
                if histogram is not None:
                    if disc_histograms is not None:
                        disc_histograms.append(histogram)
                    #plot_histogram(
                    #    ax, histogram,
                    #    color=colors_by_discriminator[disc_tuple],
                    #    label=" ".join(map(str, disc_tuple))
                    #)
                    #any_shown = True
                else:
                    disc_histograms = None
                    print(" (no data)")
            if disc_histograms is not None:
                plot_histogram(
                    ax, combine_histograms(disc_histograms),
                    color=colors_by_discriminator[disc_tuple],
                    label=" ".join(map(str, disc_tuple))
                )
                any_shown = True
        if any_shown:
            ax.set_title(f"{ops} ops/s")

            percentile_ticks = list(generate_percentile_ticks(max_percentile))
            ax.set_xscale("function", functions=(percentile_transform, percentile_transform_reverse))
            ax.axvline(0.5)
            ax.set_xticks(percentile_ticks, [str(p) for p in percentile_ticks])
            ax.set_ylabel("Request time (ns)")
            ax.set_xlim(0, max_percentile)
            ax.set_yscale("log")
            ax.set_ylim(min_time * 0.9, max_time)
            if phase_i == 0:
                ax.legend(handles=[
                    matplotlib.patches.Patch(color=v, label=" ".join(map(str, k)))
                    for k, v in colors_by_discriminator.items()
                ])
        else:
            ax.remove()

    for r in range(rows):
        for c in range(cols):
            if c + r * rows >= len(OPS):
                axs[r][c].remove()
    plt.xlabel("Percentile")
    plt.show()


main()
