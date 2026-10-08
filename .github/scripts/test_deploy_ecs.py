import copy
import unittest
from unittest.mock import patch
import deploy_ecs as deploy


class EcsDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.environment = {
            "BACKEND_SERVICE_NAME": "legal-backend", "FRONTEND_SERVICE_NAME": "legal-frontend",
            "ECS_CLUSTER": "LegalAdministrator", "AWS_REGION": "us-east-1", "IMAGE_URI": "registry/backend:sha",
            "ECS_EXECUTION_ROLE_ARN": "arn:execution", "ECS_INFRASTRUCTURE_ROLE_ARN": "arn:infra",
            "BACKEND_TASK_ROLE_ARN": "arn:task", "ECS_SUBNET_IDS": "subnet-a,subnet-b",
            "BACKEND_SECURITY_GROUP_IDS": "sg-backend", "FRONTEND_SECURITY_GROUP_IDS": "sg-frontend",
            "DB_HOST": "db.rds.amazonaws.com", "DB_NAME": "legal", "DB_USER": "app",
            "DOCUMENT_S3_BUCKET": "documents", "DB_PASSWORD_SECRET_ARN": "arn:password", "JWT_SECRET_ARN": "arn:jwt",
            "FRONTEND_URL": "https://frontend.example.com",
        }
        self.config = {"serviceRevisionArn": "revision-new", "primaryContainer": {
            "image": "old", "containerPort": 8080, "environment": [{"name": "OPTIONAL", "value": "keep"}],
            "secrets": [{"name": "OTHER", "valueFrom": "arn:other"}]},
            "ingressPaths": [{"accessType": "PUBLIC", "endpoint": "https://backend.example.com"}]}
        self.service = {"serviceArn": "arn:service", "status": {"statusCode": "ACTIVE"},
                        "currentDeployment": "deployment-new", "activeConfigurations": [self.config]}

    def test_backend_creation_uses_secret_references_and_task_role(self):
        with patch.dict(deploy.os.environ, self.environment, clear=True), patch.object(deploy, "find_service", return_value=None), patch.object(deploy, "submit") as submit:
            deploy.deploy("backend")
            payload = submit.call_args.args[0]
            self.assertEqual(payload["taskRoleArn"], "arn:task")
            self.assertEqual(payload["networkConfiguration"]["subnets"], ["subnet-a", "subnet-b"])
            secrets = {v["name"]: v["valueFrom"] for v in payload["primaryContainer"]["secrets"]}
            self.assertEqual(secrets["DB_PASSWORD"], "arn:password")
            self.assertNotIn("DB_PASSWORD", {v["name"] for v in payload["primaryContainer"]["environment"]})

    def test_update_preserves_optional_configuration(self):
        original = copy.deepcopy(self.service)
        with patch.dict(deploy.os.environ, self.environment, clear=True), patch.object(deploy, "find_service", return_value=self.service), patch.object(deploy, "submit") as submit:
            deploy.deploy("backend")
            container = submit.call_args.args[0]["primaryContainer"]
            self.assertIn({"name": "OPTIONAL", "value": "keep"}, container["environment"])
            self.assertIn({"name": "OTHER", "valueFrom": "arn:other"}, container["secrets"])
            self.assertEqual(self.service, original)

    def test_cors_update_keeps_image_and_secrets(self):
        with patch.dict(deploy.os.environ, self.environment, clear=True), patch.object(deploy, "find_service", return_value=self.service), patch.object(deploy, "submit") as submit:
            deploy.update_cors()
            container = submit.call_args.args[0]["primaryContainer"]
            self.assertEqual(container["image"], "old")
            self.assertEqual(container["secrets"], self.config["primaryContainer"]["secrets"])
            self.assertIn({"name": "FRONTEND_ALLOWED_ORIGINS", "value": self.environment["FRONTEND_URL"]}, container["environment"])

    def test_wait_ignores_previous_success_until_target_revision_is_ready(self):
        responses = [
            {"service": self.service}, {"serviceDeployments": [{"targetServiceRevision": {"arn": "old"}, "status": "SUCCESSFUL"}]},
            {"service": self.service}, {"serviceDeployments": [{"targetServiceRevision": {"arn": "revision-new"}, "status": "SUCCESSFUL"}]},
        ]
        with patch.object(deploy, "aws", side_effect=responses), patch.object(deploy.time, "sleep") as sleep:
            _, url = deploy.wait_deployment("arn:service", "revision-new")
            self.assertEqual(url, "https://backend.example.com")
            sleep.assert_called_once()

    def test_rollbacks_are_failures(self):
        with patch.object(deploy, "aws", side_effect=[{"service": self.service}, {"serviceDeployments": [{"targetServiceRevision": {"arn": "revision-new"}, "status": "ROLLBACK_SUCCESSFUL"}]}]):
            with self.assertRaisesRegex(RuntimeError, "ROLLBACK_SUCCESSFUL"):
                deploy.wait_deployment("arn:service", "revision-new")

    def test_update_without_revision_waits_for_a_new_deployment(self):
        previous = copy.deepcopy(self.service)
        previous["currentDeployment"] = "deployment-old"
        responses = [{"service": previous}, {"service": self.service},
                     {"serviceDeployments": [{"targetServiceRevision": {"arn": "revision-new"}, "status": "SUCCESSFUL"}]}]
        with patch.object(deploy, "aws", side_effect=responses), patch.object(deploy.time, "sleep") as sleep:
            _, url = deploy.wait_deployment("arn:service", previous_deployment="deployment-old")
            self.assertEqual(url, "https://backend.example.com")
            sleep.assert_called_once()

    def test_origin_rejects_http_paths_and_credentials(self):
        for value in ["http://example.com", "https://example.com/api", "https://user:pass@example.com", "https://example.com?query=1"]:
            with self.assertRaises(ValueError):
                deploy.validate_origin(value)
        self.assertEqual(deploy.validate_origin("https://frontend.example.com/"), "https://frontend.example.com")

    def test_backend_url_waits_for_initial_deployment(self):
        with patch.dict(deploy.os.environ, self.environment, clear=True), patch.object(deploy, "find_service", side_effect=[None, self.service]), patch.object(deploy.time, "sleep") as sleep:
            _, url = deploy.backend_url()
            self.assertEqual(url, "https://backend.example.com")
            sleep.assert_called_once()

    def test_cors_does_not_redeploy_when_the_origin_is_already_configured(self):
        for key in ["FRONTEND_ALLOWED_ORIGINS", "GOOGLE_FRONTEND_ORIGIN"]:
            self.config["primaryContainer"]["environment"].append({"name": key, "value": self.environment["FRONTEND_URL"]})
        with patch.dict(deploy.os.environ, self.environment, clear=True), patch.object(deploy, "find_service", return_value=self.service), patch.object(deploy, "submit") as submit:
            deploy.update_cors()
            submit.assert_not_called()


if __name__ == "__main__":
    unittest.main()
