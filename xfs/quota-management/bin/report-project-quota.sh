#!/bin/bash
set -e -u -o pipefail
#set -x

source ./_helpers.sh

declare -r prog_name=$(basename ${0})

declare -r user_dir=${1}
declare -r username=$(basename ${user_dir})
_validate_username ${username} username_local_part username_domain

declare -r project_path=${user_dir%/}
[[ -d ${project_path} ]]

# Initialize .quota directory

quota_dir="${user_dir%/}/.quota"
[[ -d ${quota_dir} ]]

mkdir -p ${quota_dir}/{report,request}
chown root:user ${quota_dir}/{report,request}
chmod 0750 ${quota_dir}/report 
chmod 0770 ${quota_dir}/request

for metric in "space" "space-used" "files" "files-used"; do
    [[ -f "${quota_dir}/report/${metric}" ]] || echo -n "0" > ${quota_dir}/report/${metric}
done

# Report for quota

read fstype mountpoint < <(df --output=fstype,target ${project_path} | sed 1d)
[[ ${fstype} == "xfs" ]]
[[ -d ${mountpoint} ]]

[[ -f ${project_path}/.project-setup-successful ]] || exit 0;

read project_id <${project_path}/.project-id 
read project_name <${project_path}/.project-name

read fs used_blocks limit_blocks r1 <\
    <(xfs_quota -x -d ${project_id} -c "quota -b -p ${project_id} -N" ${mountpoint})

declare -i used_in_bytes=$(( used_blocks * 1024 ))
declare -i limit_in_bytes=$(( limit_blocks * 1024 ))
echo -n ${used_in_bytes} >${quota_dir}/report/space-used
echo -n ${limit_in_bytes} >${quota_dir}/report/space

read fs used_files limit_files r1 <\
    <(xfs_quota -x -d ${project_id} -c "quota -i -p ${project_id} -N" ${mountpoint})

echo -n ${used_files} >${quota_dir}/report/files-used
echo -n ${limit_files} >${quota_dir}/report/files

logger -s -p local7.info -t ${prog_name} -- "Reporting quota for project of user [${username}]:"\
    "files=${limit_files} files-used=${used_files} space=${limit_in_bytes} space-used=${used_in_bytes}" 
