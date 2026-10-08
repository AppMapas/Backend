"""Deploy ECS Express Mode services on Fargate using AWS CLI.

Express Mode provisions ALB, task definitions, services and autoscaling.
"""
import copy
import json
import os
import subprocess
import sys
import time
from urllib.parse import urlsplit


def required(name):
    value = os.environ.get(name, "")
    if not value:
        raise ValueError(f"Missing configuration: {name}")
    return value


def aws(operation, payload):
    return json.loads(subprocess.check_output(
        ["aws", "ecs", operation, "--cli-input-json", json.dumps(payload), "--output", "json"], text=True))


def find_service(name):
    response = aws("describe-services", {"cluster": required("ECS_CLUSTER"), "services": [name]})
    services = [service for service in response.get("services", []) if service["status"] != "INACTIVE"]
    if not services:
        failures = response.get("failures", [])
        if any(failure.get("reason") != "MISSING" for failure in failures):
            raise RuntimeError(f"Could not look up ECS service: {failures}")
        return None
    return aws("describe-express-gateway-service", {"serviceArn": services[0]["serviceArn"]})["service"]


def configuration(service):
    configurations = service.get("activeConfigurations", [])
    if len(configurations) != 1:
        raise RuntimeError("Service has an active rollout; wait for it to finish before deploying.")
    return copy.deepcopy(configurations[0])


def public_url(config):
    public = next((p["endpoint"] for p in config.get("ingressPaths", []) if p["accessType"] == "PUBLIC"), None)
    if not public:
        raise RuntimeError("No public endpoint returned by ECS Express Mode")
    url = public if "://" in public else "https://" + public
    return validate_origin(url)


def validate_origin(url):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
        raise ValueError("Production URL must be an HTTPS origin without a path, query or credentials.")
    return url.rstrip("/")


def wait_deployment(arn, revision=None, timeout=1800, previous_deployment=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        service = aws("describe-express-gateway-service", {"serviceArn": arn})["service"]
        if service["status"]["statusCode"] in {"DRAINING", "INACTIVE"}:
            raise RuntimeError(f"ECS service unavailable: {service['status']}")
        deployment_arn = service.get("currentDeployment")
        if deployment_arn and (revision is not None or deployment_arn != previous_deployment):
            deployments = aws("describe-service-deployments", {"serviceDeploymentArns": [deployment_arn]}).get("serviceDeployments", [])
            if deployments:
                deployment = deployments[0]
                target = deployment["targetServiceRevision"]["arn"]
                if revision is None or target == revision:
                    status = deployment["status"]
                    if status in {"STOPPED", "STOP_REQUESTED", "ROLLBACK_REQUESTED", "ROLLBACK_IN_PROGRESS", "ROLLBACK_SUCCESSFUL", "ROLLBACK_FAILED"}:
                        raise RuntimeError(f"ECS deployment failed: {status}. Review ECS events and CloudWatch logs.")
                    if status == "SUCCESSFUL":
                        config = next((c for c in service.get("activeConfigurations", []) if c["serviceRevisionArn"] == target), None)
                        if config:
                            return service, public_url(config)
        time.sleep(10)
    raise TimeoutError(f"ECS deployment exceeded {timeout}s")


def submit(payload, existing):
    operation = "update-express-gateway-service" if existing else "create-express-gateway-service"
    service = aws(operation, payload)["service"]
    revision = service.get("targetConfiguration", {}).get("serviceRevisionArn")
    previous = existing.get("currentDeployment") if existing else None
    return wait_deployment(service["serviceArn"], revision, previous_deployment=previous)


def deploy(kind):
    name = required(f"{kind.upper()}_SERVICE_NAME")
    existing = find_service(name)
    config = configuration(existing) if existing else {}
    container = config.get("primaryContainer", {})
    container.update({"image": required("IMAGE_URI"), "containerPort": 8080})
    payload = {
        "executionRoleArn": required("ECS_EXECUTION_ROLE_ARN"), "primaryContainer": container,
        "healthCheckPath": "/actuator/health" if kind == "backend" else "/",
        "cpu": "1024" if kind == "backend" else "256",
        "memory": "2048" if kind == "backend" else "512",
        "networkConfiguration": {
            "subnets": [item.strip() for item in required("ECS_SUBNET_IDS").split(",")],
            "securityGroups": [item.strip() for item in required(f"{kind.upper()}_SECURITY_GROUP_IDS").split(",")],
        },
        "scalingTarget": {"minTaskCount": 1, "maxTaskCount": 3, "autoScalingMetric": "AVERAGE_CPU", "autoScalingTargetValue": 70},
    }
    if existing:
        payload["serviceArn"] = existing["serviceArn"]
    else:
        payload.update({"serviceName": name, "cluster": required("ECS_CLUSTER"),
                        "infrastructureRoleArn": required("ECS_INFRASTRUCTURE_ROLE_ARN")})
    if kind == "backend":
        variables = {item["name"]: item["value"] for item in container.get("environment", [])}
        variables.update({"SPRING_PROFILES_ACTIVE": "prod", "AWS_REGION": required("AWS_REGION"),
                          "DB_HOST": required("DB_HOST"), "DB_PORT": os.environ.get("DB_PORT", "5432"),
                          "DB_NAME": required("DB_NAME"), "DB_USER": required("DB_USER"),
                          "DOCUMENT_STORAGE_PROVIDER": "s3", "DOCUMENT_S3_BUCKET": required("DOCUMENT_S3_BUCKET")})
        variables.setdefault("FRONTEND_ALLOWED_ORIGINS", "https://pending.invalid")
        variables.pop("DB_PASSWORD", None)
        variables.pop("JWT_SECRET", None)
        container["environment"] = [{"name": key, "value": value} for key, value in variables.items()]
        secrets = {item["name"]: item["valueFrom"] for item in container.get("secrets", [])}
        secrets.update({"DB_PASSWORD": required("DB_PASSWORD_SECRET_ARN"), "JWT_SECRET": required("JWT_SECRET_ARN")})
        container["secrets"] = [{"name": key, "valueFrom": value} for key, value in secrets.items()]
        payload["taskRoleArn"] = required("BACKEND_TASK_ROLE_ARN")
    return submit(payload, existing)


def update_cors():
    existing = find_service(required("BACKEND_SERVICE_NAME"))
    if not existing:
        raise RuntimeError("Backend service does not exist")
    container = configuration(existing)["primaryContainer"]
    variables = {item["name"]: item["value"] for item in container.get("environment", [])}
    origin = validate_origin(required("FRONTEND_URL"))
    if variables.get("FRONTEND_ALLOWED_ORIGINS") == origin and variables.get("GOOGLE_FRONTEND_ORIGIN") == origin:
        return existing, public_url(configuration(existing))
    variables.update({"FRONTEND_ALLOWED_ORIGINS": origin, "GOOGLE_FRONTEND_ORIGIN": origin})
    container["environment"] = [{"name": key, "value": value} for key, value in variables.items()]
    return submit({"serviceArn": existing["serviceArn"], "primaryContainer": container}, existing)


def backend_url(timeout=1800):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        service = find_service(required("BACKEND_SERVICE_NAME"))
        if service and len(service.get("activeConfigurations", [])) == 1:
            config = configuration(service)
            if config.get("ingressPaths"):
                return service, public_url(config)
        time.sleep(10)
    raise TimeoutError("Backend URL unavailable. Deploy the backend first.")


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in {"backend", "frontend", "cors", "backend-url"}:
        raise ValueError("Usage: deploy_ecs.py backend|frontend|cors|backend-url")
    mode = sys.argv[1]
    service, url = backend_url() if mode == "backend-url" else update_cors() if mode == "cors" else deploy(mode)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"url={url}\narn={service['serviceArn']}\n")
    print(f"Service running: {url}")


if __name__ == "__main__":
    main()
