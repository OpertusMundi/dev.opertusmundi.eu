#!/bin/bash
set -e -u -o pipefail

source ./_helpers.sh

_find_user_dirs ${1} | xargs -n1 ./bin/report-project-quota.sh
