#!/usr/bin/env python3
"""Stream a trusted OSM XML snapshot into a PostgreSQL road-identity catalog."""
import argparse
import hashlib
import re
import sys
import xml.etree.ElementTree as ET

HIGHWAYS = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
            "residential", "living_street", "service", "road", "track",
            "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"}

def road_edges(path):
    context = ET.iterparse(path, events=("start", "end"))
    _, root = next(context)
    for event, element in context:
        if event != "end" or element.tag not in {"node", "way", "relation"}:
            continue
        if element.tag == "way":
            tags = {tag.attrib["k"]: tag.attrib["v"] for tag in element.findall("tag")}
            denied = any(tags.get(k) in {"no", "private"} for k in ("access", "vehicle", "motor_vehicle", "motorcar"))
            if tags.get("highway") in HIGHWAYS and not denied and tags.get("area") != "yes":
                way = int(element.attrib["id"])
                nodes = [int(nd.attrib["ref"]) for nd in element.findall("nd")]
                if way <= 0 or any(n <= 0 for n in nodes):
                    raise ValueError("Positive OSM identifiers required")
                for a, b in zip(nodes, nodes[1:]):
                    if a != b:
                        yield min(a, b), max(a, b), way
        root.clear()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot", help="The same .osm XML file used by osrm-extract")
    parser.add_argument("--data-version", required=True, help="Exactly the OSRM data_version value")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9TZ:.+\-]{10,40}", args.data_version):
        parser.error("data-version must be an ISO timestamp")
    with open(args.snapshot, "rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    print("BEGIN;")
    print(f"INSERT INTO road_catalogs(dataset,data_version) VALUES ('{digest}','{args.data_version}');")
    print("CREATE TEMP TABLE catalog_import(node_low BIGINT,node_high BIGINT,way_id BIGINT) ON COMMIT DROP;")
    print("COPY catalog_import FROM STDIN;")
    count = 0
    for a, b, way in road_edges(args.snapshot):
        print(f"{a}\t{b}\t{way}")
        count += 1
    print("\\.")
    if not count:
        raise ValueError("The snapshot contains no eligible road edges")
    # Shared/overlapping OSM ways are left ambiguous and must not earn competitive credit.
    print(f"INSERT INTO road_edges SELECT '{digest}',node_low,node_high,"
          "CASE WHEN COUNT(DISTINCT way_id)=1 THEN MIN(way_id) ELSE NULL END "
          "FROM catalog_import GROUP BY node_low,node_high;")
    print("COMMIT;")
    print(f"ROAD_DATASET_SHA256={digest}", file=sys.stderr)

if __name__ == "__main__":
    main()
