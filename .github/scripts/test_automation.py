import os
import unittest
from unittest.mock import MagicMock, patch
import validate_deployment as validation
import verify_runtime as runtime


class AutomationTest(unittest.TestCase):
    def setUp(self):
        self.values = {
            "AWS_REGION": "us-east-1", "ECS_EXECUTION_ROLE_ARN": "arn:aws:iam::123456789012:role/execution",
            "ECS_INFRASTRUCTURE_ROLE_ARN": "arn:aws:iam::123456789012:role/infra",
            "BACKEND_TASK_ROLE_ARN": "arn:aws:iam::123456789012:role/backend",
            "ECS_SUBNET_IDS": "subnet-01234567,subnet-89abcdef", "BACKEND_SECURITY_GROUP_IDS": "sg-01234567",
            "FRONTEND_SECURITY_GROUP_IDS": "sg-01234567",
            "DB_PASSWORD_SECRET_ARN": "arn:aws:secretsmanager:us-east-1:123456789012:secret:db-password",
            "JWT_SECRET_ARN": "arn:aws:secretsmanager:us-east-1:123456789012:secret:jwt",
            "BACKEND_URL": "https://backend.example.com", "FRONTEND_URL": "https://frontend.example.com",
        }

    def response(self, body=b'<div id="app"></div>', headers=None):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = body
        response.headers = headers or {}
        return response

    def test_valid_variables_in_both_components(self):
        self.assertEqual(validation.validate("backend", self.values), [])
        self.assertEqual(validation.validate("frontend", self.values), [])

    def test_frontend_needs_no_database_secrets(self):
        self.values.pop("DB_PASSWORD_SECRET_ARN")
        self.values.pop("JWT_SECRET_ARN")
        self.assertEqual(validation.validate("frontend", self.values), [])
        self.assertTrue(validation.validate("backend", self.values))

    def test_invalid_network_and_role_identifiers_fail(self):
        self.values.update(ECS_SUBNET_IDS="invalid", ECS_EXECUTION_ROLE_ARN="invalid")
        self.assertEqual(len(validation.validate("frontend", self.values)), 2)

    def test_healthy_backend(self):
        with patch.dict(os.environ, self.values), patch.object(runtime, "urlopen", return_value=self.response(b'{"status":"UP"}')):
            runtime.health()

    def test_unhealthy_backend_fails(self):
        with patch.dict(os.environ, self.values), patch.object(runtime, "urlopen", return_value=self.response(b'{"status":"DOWN"}')):
            with self.assertRaises(RuntimeError):
                runtime.health()

    def test_real_preflight_contract_is_checked(self):
        headers = {"Access-Control-Allow-Origin": self.values["FRONTEND_URL"], "Access-Control-Allow-Methods": "GET,POST"}
        with patch.dict(os.environ, self.values), patch.object(runtime, "urlopen", side_effect=[self.response(), self.response(), self.response(headers=headers)]) as request:
            runtime.cors()
            self.assertEqual(request.call_args.args[0].get_method(), "OPTIONS")

    def test_wrong_cors_origin_fails(self):
        headers = {"Access-Control-Allow-Origin": "https://wrong.example.com", "Access-Control-Allow-Methods": "POST"}
        with patch.dict(os.environ, self.values), patch.object(runtime, "urlopen", side_effect=[self.response(), self.response(), self.response(headers=headers)]):
            with self.assertRaisesRegex(RuntimeError, "origin"):
                runtime.cors()


if __name__ == "__main__":
    unittest.main()
