#!/bin/bash
set -e -u -o pipefail
#set -x

source ./_constants.sh
source ./_helpers.sh

declare -r prog_name=$(basename ${0})

declare -r user_dir=${1}
declare -r username=$(basename ${user_dir})
_validate_username ${username} username_local_part username_domain

declare -r project_path=${user_dir%/}
[[ -d ${project_path} ]]

quota_dir="${user_dir%/}/.quota"
[[ -d ${quota_dir} ]]
[[ -d ${quota_dir}/request ]] || exit 0;

read fstype mountpoint < <(df --output=fstype,target ${project_path} | sed 1d)
[[ ${fstype} == "xfs" ]]
[[ -d ${mountpoint} ]]

[[ -f ${project_path}/.project-setup-successful ]] || exit 0;

read project_id <${project_path}/.project-id 
read project_name <${project_path}/.project-name

# Process pending requests (space)

readarray -t requests_for_space <\
    <(ls -1t ${quota_dir}/request | grep -xo -e 'space[.][1-9][0-9]\+')
if (( ${#requests_for_space[@]} > 0 )); then
    for r in ${requests_for_space[@]:1}; do
        mv -v ${quota_dir}/request/${r} ${quota_dir}/request/${r}.superseded
    done
    # Handle request
    r0=${requests_for_space[0]}
    s0=
    read n0 <${quota_dir}/request/${r0}
    if [[ ! ${n0} =~ ^[-]?[1-9][0-9]*$ ]] || (( n0 <= 0 )) || (( n0 > max_space_limit_in_bytes )); then
        logger -s -p local7.error -t ${prog_name} -- "Rejected request [${r0}] for project #${project_id}"\
            "(size [${n0}] is out of range (0..${max_space_limit_in_bytes}])"
        s0="rejected"
    else
        bsoft=${n0}
        bhard=$(( (n0 * (100 + default_space_hard_limit_percent_cap)) / 100 ))
        (set -x -e; xfs_quota -x -d ${project_id} -c "limit -p bhard=${bhard} bsoft=${bsoft} ${project_id}" ${mountpoint})
        s0="accepted"
    fi
    mv -v ${quota_dir}/request/${r0} ${quota_dir}/request/${r0}.${s0}
fi

# Process pending requests (files)

readarray -t requests_for_files <\
    <(ls -1t ${quota_dir}/request | grep -xo -e 'files[.][1-9][0-9]\+')
if (( ${#requests_for_files[@]} > 0 )); then
    for r in ${requests_for_files[@]:1}; do
        mv -v ${quota_dir}/request/${r} ${quota_dir}/request/${r}.superseded
    done
    # Handle request
    r0=${requests_for_files[0]}
    s0=
    read n0 <${quota_dir}/request/${r0}
    if [[ ! ${n0} =~ ^[-]?[1-9][0-9]*$ ]] || (( n0 <= 0 )) || (( n0 > max_files_limit )); then
        logger -s -p local7.error -t ${prog_name} -- "Rejected request [${r0}] for project #${project_id}"\
            "(number [${n0}] is out of range (0..${max_files_limit}])"
        s0="rejected"
    else
        isoft=${n0}
        ihard=$(( (n0 * (100 + default_files_hard_limit_percent_cap)) / 100 ))
        (set -x -e; xfs_quota -x -d ${project_id} -c "limit -p ihard=${ihard} isoft=${isoft} ${project_id}" ${mountpoint})
        s0="accepted"
    fi
    mv -v ${quota_dir}/request/${r0} ${quota_dir}/request/${r0}.${s0}
fi
