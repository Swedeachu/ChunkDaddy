"""Publication guards: staging must not look like a concurrent edit or hide a real one."""
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from build_sg_events import sha256
from update_sg_event_maps import assert_inputs, discover, snapshot_files, patch_manifest_position


class UpdateGuardsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sg = self.root / 'plugins/SwimCore/data/maps/sg'
        (self.sg / 'SGA').mkdir(parents=True)
        (self.sg / 'SGA/storm-map.bin').write_bytes(b'old image')
        (self.sg / 'regions.json').write_bytes(b'original positions')
        (self.root / 'savedWorlds').mkdir()
        (self.root / 'savedWorlds/SG_EVENTS.zip').write_bytes(b'old world')
        self.source = self.root / 'SGA (fixed).mcworld'
        self.source.write_bytes(b'edited source')
        self.args = SimpleNamespace(swim_root=self.root)
        self.report = {'originalRuntimeAssets': snapshot_files(self.sg),
                       'originalWorlds': {'SG_EVENTS': sha256(self.root / 'savedWorlds/SG_EVENTS.zip')},
                       'updates': {'SGA': {'sourcePath': str(self.source), 'sourceSha256': sha256(self.source)}}}

    def test_own_pending_image_is_allowed_only_during_publication(self):
        (self.sg / 'SGA/storm-map.bin.pending').write_bytes(b'new staged image')
        with self.assertRaises(ValueError): assert_inputs(self.args, self.report)
        assert_inputs(self.args, self.report, allow_pending=True)
        (self.sg / 'SGA/storm-map.bin').write_bytes(b'concurrent real image edit')
        with self.assertRaises(ValueError): assert_inputs(self.args, self.report, allow_pending=True)

    def test_pending_allowance_never_hides_other_files_or_source_changes(self):
        (self.sg / 'foreign.pending').write_bytes(b'external change')
        with self.assertRaises(ValueError): assert_inputs(self.args, self.report, allow_pending=True)
        (self.sg / 'foreign.pending').unlink()
        self.source.write_bytes(b'different edited source')
        with self.assertRaises(ValueError): assert_inputs(self.args, self.report, allow_pending=True)

    def test_installed_world_change_blocks_publication(self):
        (self.root / 'savedWorlds/SG_EVENTS.zip').write_bytes(b'concurrent world edit')
        with self.assertRaises(ValueError): assert_inputs(self.args, self.report, allow_pending=True)

    def test_filename_aliases_and_unknown_names(self):
        incoming = self.root / 'incoming'
        incoming.mkdir()
        for name in ['SG4 (Fixed)', 'Turbulance (fixed)', 'TheZone85 (fixed)', 'garrigal fixed']:
            (incoming / (name + '.mcworld')).touch()
        self.assertEqual(set(discover(incoming, {'SGFOUR', 'turbulence', 'zone85', 'Garrigal'})), {'SGFOUR', 'turbulence', 'zone85', 'Garrigal'})
        (incoming / 'unknown.mcworld').touch()
        with self.assertRaises(ValueError): discover(incoming, {'SGFOUR', 'turbulence', 'zone85', 'Garrigal'})

    def test_exact_source_position_correction_preserves_offsets_and_other_points(self):
        manifest = {'templates': {'Forlorn': {'sourceBounds': {'minChunkX': -30, 'minChunkZ': -32},
                    'metadata': {'pointsOfInterest': [{'x': 548, 'y': 72, 'z': 542}, {'x': 123, 'y': 20, 'z': 456}]}}},
                    'instances': [{'offset': {'x': 1000, 'y': 0, 'z': 2000}}]}
        patch = {'template': 'Forlorn', 'field': 'pointsOfInterest', 'from': {'x': 68, 'y': 72, 'z': 30}, 'to': {'x': 68, 'y': 76, 'z': 30}}
        patch_manifest_position(manifest, patch)
        self.assertEqual(manifest['templates']['Forlorn']['metadata']['pointsOfInterest'], [{'x': 548, 'y': 76, 'z': 542}, {'x': 123, 'y': 20, 'z': 456}])
        self.assertEqual(manifest['instances'], [{'offset': {'x': 1000, 'y': 0, 'z': 2000}}])
        with self.assertRaises(ValueError): patch_manifest_position(manifest, patch)

    def test_position_publication_only_allows_its_explicit_pending_assets(self):
        self.report['runtimeAssetChanges'] = {'regions.json': 'new checksum'}
        (self.sg / 'regions.json.pending').write_bytes(b'corrected manifest')
        assert_inputs(self.args, self.report, allow_pending=True)
        (self.sg / 'regions.json').write_bytes(b'concurrent manifest edit')
        with self.assertRaises(ValueError): assert_inputs(self.args, self.report, allow_pending=True)


if __name__ == '__main__': unittest.main()
