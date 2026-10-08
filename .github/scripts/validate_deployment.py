"""Offline validation of GitHub variables or a local .env.aws file."""
import argparse
import os
import re
from pathlib import Path


def validate(component, values):
    required = ["AWS_REGION", "ECS_EXECUTION_ROLE_ARN", "ECS_INFRASTRUCTURE_ROLE_ARN", "ECS_SUBNET_IDS",
                f"{component.upper()}_SECURITY_GROUP_IDS"]
    if component == "backend":
        required += ["BACKEND_TASK_ROLE_ARN", "DB_PASSWORD_SECRET_ARN", "JWT_SECRET_ARN"]
    errors = []
    for name in required:
        value = values.get(name, "").strip()
        if not value or "REPLACE" in value:
            errors.append(f"{name}: missing value")
            continue
        if name.endswith("ROLE_ARN") and not re.fullmatch(r"arn:aws(?:-[a-z]+)*:iam::\d{12}:role/.+", value):
            errors.append(f"{name}: invalid IAM role ARN")
        if name == "AWS_REGION" and not re.fullmatch(r"[a-z]{2}(?:-[a-z]+)+-\d+", value):
            errors.append(f"{name}: invalid AWS region")
        if name.endswith("SECRET_ARN") and not re.fullmatch(r"arn:aws(?:-[a-z]+)*:secretsmanager:[a-z0-9-]+:\d{12}:secret:.+", value):
            errors.append(f"{name}: invalid Secrets Manager ARN")
        if name.endswith("SUBNET_IDS") or name.endswith("SECURITY_GROUP_IDS"):
            prefix = "subnet" if name.endswith("SUBNET_IDS") else "sg"
            if any(not re.fullmatch(prefix + r"-[0-9a-f]{8}(?:[0-9a-f]{9})?", item.strip()) for item in value.split(",")):
                errors.append(f"{name}: invalid comma-separated resource IDs")
    return errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("component", choices=["backend", "frontend"])
    parser.add_argument("--env-file", type=Path)
    args = parser.parse_args()
    values = dict(os.environ)
    if args.env_file:
        for line in args.env_file.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    errors = validate(args.component, values)
    if errors:
        parser.exit(1, "Configuration errors:\n" + "\n".join(errors) + "\n")
    print(f"{args.component}: deployment variables valid (offline; AWS resources not queried).")


if __name__ == "__main__":
    main()
