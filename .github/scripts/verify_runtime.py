"""Verify deployed health, SPA routes and browser CORS using HTTPS."""
import json
import sys
from urllib.request import Request, urlopen
from deploy_ecs import required, validate_origin


def health():
    backend = validate_origin(required("BACKEND_URL"))
    with urlopen(backend + "/actuator/health", timeout=30) as response:
        if response.status != 200 or json.load(response).get("status") != "UP":
            raise RuntimeError("Backend health check failed")
    print("Backend health: UP")


def cors():
    backend = validate_origin(required("BACKEND_URL"))
    frontend = validate_origin(required("FRONTEND_URL"))
    with urlopen(frontend + "/", timeout=30) as response:
        home = response.read()
        if response.status != 200 or b'id="app"' not in home:
            raise RuntimeError("Frontend entrypoint missing")
    with urlopen(frontend + "/expedientes/6", timeout=30) as response:
        if response.status != 200 or response.read() != home:
            raise RuntimeError("Nginx SPA fallback failed")
    request = Request(backend + "/api/v1/auth/login", method="OPTIONS", headers={
        "Origin": frontend, "Access-Control-Request-Method": "POST", "Access-Control-Request-Headers": "content-type,authorization"})
    with urlopen(request, timeout=30) as response:
        if response.headers.get("Access-Control-Allow-Origin") != frontend:
            raise RuntimeError("Backend did not allow the deployed frontend origin")
        if "POST" not in response.headers.get("Access-Control-Allow-Methods", ""):
            raise RuntimeError("Backend CORS does not allow POST")
    print("Frontend SPA and backend CORS verified")


if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in {"health", "cors"}:
        raise ValueError("Usage: verify_runtime.py health|cors")
    health() if sys.argv[1] == "health" else cors()
