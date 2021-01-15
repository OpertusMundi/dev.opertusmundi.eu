# README

This recipe sets up a [Drone CI](https://docs.drone.io/) server.

## Prequisites

Provide files `certs/server.{key,crt}` for the TLS certificate of the CI server.

Copy `.env.example` into `.env` and configure to your needs.

## Examples

For Docker-based pipelines see also: https://docs.drone.io/pipeline/docker/overview/

### Example - Use Docker-in-Docker for testing

An example pipeline would be like (in a helloworld project):
```yml
kind: pipeline
type: docker
name: testing-1

steps:
- name: testing-in-docker
  image: docker:19.03-dind
  when:
    event:
    - push
    - tag
  volumes:
  - name: docker_run
    path: /var/run
  environment:
    DOCKER_HOST: unix:///var/run/docker.sock
  commands:
  # wait for Docker service to be up
  - >-
    (t=0; T=5; while ! docker info -f '{{.ID}}' 2>/dev/null; do t=$(( t + 1 )); test ${t} -ne ${T}; sleep 1; done)
  - docker info
  # build the image for testing
  - docker build . -f testing.dockerfile -t helloworld:${DRONE_COMMIT}-testing
  # run nosetests inside a testing container
  - docker run --rm -u 1000:1000 -v $PWD:/work -w /work/ helloworld:${DRONE_COMMIT}-testing

services:
- name: docker
  image: docker:19.03-dind
  privileged: true
  command:
  # optional: use a registry mirror to cache images from Docker Hub
  - --registry-mirror=http://registry-mirror:5000
  volumes:
  - name: docker_run
    path: /var/run

volumes:
- name: docker_run
  temp: {}
```

### Example - Build Docker image

An example step of a pipeline would be like :
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
    # use a mirror registry instead of pulling images directly from the central Hub
    #mirror: http://registry-mirror:5000  
    # registry to push image to
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

