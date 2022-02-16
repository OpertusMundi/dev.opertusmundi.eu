#!/bin/bash
set -e -u -o pipefail
#set -x

source ./_constants.sh
source ./_helpers.sh

declare -r user_dir=${1}
declare -r username=$(basename ${user_dir})
_validate_username ${username} username_local_part username_domain

declare -r project_path=${user_dir%/}
[[ -d ${project_path} ]]

declare -r project_id_seq_file=$(realpath ~/.project-id-seq)
declare -r project_id_seq_lock_file=$(realpath ~/.project-id-seq-lock)
[[ -f ${project_id_seq_file} ]]
[[ -f ${project_id_seq_lock_file} ]]

#
# Determine project name/id
#

read fstype mountpoint < <(df --output=fstype,target ${project_path} | sed 1d)
[[ ${fstype} == "xfs" ]]
[[ -d ${mountpoint} ]]

read project_name < \
    <(echo -n "${username}" | sha256sum | awk '{printf("user_%s\n", $1)}')

project_id=
project_line=$(awk -F ':' -v name1=${project_name} -- '($0 !~ /^[#]/ && $1 == name1) {print $0}' /etc/projid)
if [[ -z "${project_line}" ]]; then
    # acquire a project id: create a new project
    project_id=$(_incr_and_get_sequence_number_from_file ${project_id_seq_file} ${project_id_seq_lock_file})
    echo "${project_name}:${project_id}" >> /etc/projid
    echo "${project_id}:${project_path}" >> /etc/projects
    [[ ! -e ${project_path}/.project-name ]] && echo ${project_name} >${project_path}/.project-name
    [[ ! -e ${project_path}/.project-id ]] && echo ${project_id} >${project_path}/.project-id
else
    # read the existing project id
    IFS=':' read n1 project_id <<<${project_line}
    [[ ${n1} == ${project_name} ]]
    read existing_project_id <${project_path}/.project-id
    [[ ${project_id} == ${existing_project_id} ]]
    read existing_project_name <${project_path}/.project-name
    [[ ${project_name} == ${existing_project_name} ]]
fi

#
# Setup project for XFS quota
#

if grep -qF -e 'project identifier is not set' <\
        <(xfs_quota -x -d ${project_id} -c "project -c ${project_id}" ${mountpoint})
then    
    (set -x -e; xfs_quota -x -c "project -s ${project_id}" ${mountpoint})
    : >${project_path}/.project-setup-successful
else
    [[ -f "${project_path}/.project-setup-successful" ]]
fi

#
# Assign quota for space (blocks)
#

read fs used_blocks limit_blocks hard_limit_blocks rem1 <\
    <(xfs_quota -x -d ${project_id} -c "quota -b -p ${project_id} -N" ${mountpoint})

if (( limit_blocks == 0 )); then
    # assign default limits for space
    bsoft=${default_space_limit_in_bytes}
    bhard=${default_space_hard_limit_in_bytes}
    (set -x -e; xfs_quota -x -d ${project_id} -c "limit -p bhard=${bhard} bsoft=${bsoft} ${project_id}" ${mountpoint})
fi

#
# Assign quota for files (inodes)
#

read fs used_files limit_files hard_limit_files rem1 <\
    <(xfs_quota -x -d ${project_id} -c "quota -i -p ${project_id} -N" ${mountpoint})

if (( limit_files == 0 )); then
    # assign default limits for files
    isoft=${default_files_limit}
    ihard=${default_files_hard_limit}
    (set -x -e; xfs_quota -x -d ${project_id} -c "limit -p ihard=${ihard} isoft=${isoft} ${project_id}" ${mountpoint})
fi

