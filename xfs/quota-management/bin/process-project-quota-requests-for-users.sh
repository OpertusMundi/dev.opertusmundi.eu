#!/bin/bash
set -e -u -o pipefail

source ./_helpers.sh

_find_user_dirs -f temporary-email-address-domains/temporary-email-address-domains ${1} |\
    xargs -n 1 ./bin/process-project-quota-request.sh
