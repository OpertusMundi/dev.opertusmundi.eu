# README

This recipe sets up a [Drone CI](https://docs.drone.io/) server.

## Prequisites

Provide files `certs/server.{key,crt}` for the TLS certificate of the CI server.

Copy `.env.example` into `.env` and configure to your needs.

## Examples

For Docker-based pipelines see also: https://docs.drone.io/pipeline/docker/overview/

An example pipeline (inside a project's `.drone.yml`) would be like:
```yml
# A step that builds the Docker image using Docker-in-Docker (see https://docs.drone.io/plugins/popular/docker/)
- name: docker-build
  image: plugins/docker
  when:
    event:
    - tag
  # https://docs.drone.io/plugins/popular/docker/#parameters
  settings:
    debug: true
    username: user
    password:
      from_secret: registry_password
    registry: |-
      ${REGISTRY_HOST}:${REGISTRY_PORT}
    repo: |-
      ${REGISTRY_HOST}:${REGISTRY_PORT}/hello-world
    tags:
    - '1'
    - ${DRONE_TAG}
    build_args:
    - VERSION=${DRONE_TAG}

```

