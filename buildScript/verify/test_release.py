import unittest
from release import validate_version


class ReleaseVersionTest(unittest.TestCase):
    def setUp(self):
        self.old = dict(PACKAGE_NAME='test.app', VERSION_NAME='1.6.0', VERSION_CODE='49')
        self.new = dict(self.old, VERSION_NAME='1.6.1', VERSION_CODE='50')

    def test_tag_cannot_relabel_old_metadata(self):
        with self.assertRaisesRegex(ValueError, 'VERSION_NAME'):
            validate_version('1.6.1', self.old, [])

    def test_name_bump_requires_code_bump(self):
        for code in ('48', '49'):
            with self.assertRaisesRegex(ValueError, 'VERSION_CODE'):
                validate_version('1.6.1', dict(self.new, VERSION_CODE=code), [('1.6.0', self.old)])

    def test_new_version_and_same_tag_rebuild(self):
        self.assertEqual(250, validate_version('v1.6.1', self.new, [('1.6.0', self.old)]))
        self.assertEqual(245, validate_version('1.6.0', self.old, [('1.6.0', self.old)]))
        with self.assertRaises(ValueError):
            validate_version('v1.6.0', self.old, [('1.6.0', self.old)])

    def test_upstream_package_tags_do_not_set_fork_version(self):
        upstream = dict(self.old, PACKAGE_NAME='upstream.app', VERSION_CODE='10000')
        self.assertEqual(250, validate_version('1.6.1', self.new, [('2.0.0', upstream)]))
