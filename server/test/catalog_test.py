import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("catalog", Path(__file__).parents[1] / "tools/catalog.py")
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)

class CatalogTests(unittest.TestCase):
    def test_canonical_ids_reverse_direction_unnamed_roads_and_motor_access(self):
        xml = '''<osm><node id="10" lat="0" lon="0"/>
        <way id="100"><nd ref="12"/><nd ref="11"/><nd ref="10"/><tag k="highway" v="residential"/></way>
        <way id="101"><nd ref="20"/><nd ref="21"/><tag k="highway" v="footway"/></way>
        <way id="102"><nd ref="30"/><nd ref="31"/><tag k="highway" v="service"/><tag k="motorcar" v="no"/></way>
        <way id="103"><nd ref="40"/><nd ref="41"/><tag k="highway" v="residential"/><tag k="name" v="Same name"/></way>
        <way id="104"><nd ref="50"/><nd ref="51"/><tag k="highway" v="residential"/><tag k="name" v="Same name"/></way>
        </osm>'''
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "roads.osm"
            path.write_text(xml)
            self.assertEqual(list(catalog.road_edges(path)), [(11, 12, 100), (10, 11, 100), (40, 41, 103), (50, 51, 104)])

    def test_import_rejects_nonpositive_identifiers(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "roads.osm"
            path.write_text('<osm><way id="1"><nd ref="0"/><nd ref="2"/><tag k="highway" v="residential"/></way></osm>')
            with self.assertRaises(ValueError):
                list(catalog.road_edges(path))

if __name__ == "__main__":
    unittest.main()
