"""The reference-world scorer must recognize Java/Bedrock thin-block aliases."""
import unittest
from sketchrip_truth_calibration import bedrock_base_material
from sketchrip_truth_compare import material_of

class ReferenceThinAliases(unittest.TestCase):
    def test_snow_layer_identity(self):
        self.assertEqual(material_of('minecraft:snow[layers=2]'),
                         material_of(bedrock_base_material('minecraft:snow_layer[height=1]')))
    def test_colored_carpet_identity(self):
        for color in ('green','gray','light_gray','red','yellow'):
            bedrock_color='silver' if color=='light_gray' else color
            self.assertEqual(material_of('minecraft:'+color+'_carpet'),
                material_of(bedrock_base_material('minecraft:carpet[color='+bedrock_color+']')))

if __name__=='__main__':unittest.main()
