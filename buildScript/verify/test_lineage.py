import unittest
from lineage import validate_output


class LineageTest(unittest.TestCase):
    old, new, other = 'A' * 64, 'B' * 64, 'C' * 64

    def output(self, certificates):
        return '\n'.join(f'Signer #{i} in lineage certificate SHA-256 digest: {cert.lower()}'
                         for i, cert in enumerate(certificates, 1))

    def test_exact_ordered_certificate_history(self):
        self.assertEqual([self.old, self.new], validate_output(self.output([self.old, self.new]), self.new, self.old))

    def test_wrong_root_reversed_missing_and_extra_history_fail(self):
        for certificates in ([], [self.new], [self.other, self.new], [self.new, self.old], [self.old, self.other, self.new]):
            with self.subTest(certificates=certificates), self.assertRaises(ValueError):
                validate_output(self.output(certificates), self.new, self.old)

    def test_current_signer_and_public_key_digests_cannot_masquerade_as_history(self):
        for text in (f'Signer #1 certificate SHA-256 digest: {self.new}',
                     self.output([self.old, self.new]).replace('certificate SHA-256', 'public key SHA-256')):
            with self.assertRaises(ValueError):
                validate_output(text, self.new, self.old)
