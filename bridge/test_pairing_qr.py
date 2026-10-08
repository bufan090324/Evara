import unittest
from pairing_qr import pairing_matrix


def sample_pairing():
    return dict(protocol=1,host="192.168.1.4",port=8765,name="测试电脑",certificate_der="A"*1100,certificate_sha256="1"*64,token="synthetic_test_token_"+"A"*44)


class PairingQrTests(unittest.TestCase):
    def test_existing_pairing_generates_square_matrix_with_quiet_border(self):
        matrix=pairing_matrix(sample_pairing())
        self.assertTrue(all(len(row)==len(matrix) for row in matrix))
        self.assertTrue(all(not any(row) for row in matrix[:4]+matrix[-4:]))
        self.assertGreater(sum(map(sum,matrix)),100)

    def test_private_key_and_extra_data_never_change_qr(self):
        pairing=sample_pairing(); expected=pairing_matrix(pairing)
        pairing.update(private_key="NOT_A_REAL_KEY",health_report="NOT_REAL_HEALTH_DATA")
        self.assertEqual(expected,pairing_matrix(pairing))

    def test_missing_data_or_excessive_size_returns_clear_error(self):
        with self.assertRaisesRegex(ValueError,"不完整"):pairing_matrix({})
        pairing=sample_pairing();pairing["name"]="测"*1000
        with self.assertRaisesRegex(ValueError,"容量"):pairing_matrix(pairing)
