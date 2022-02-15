#!/bin/bash

function _validate_username()
{
    declare -n x1=${2}
    declare -n x2=${3}
    
    IFS='@' read x1 x2 x3 <<<${1}
    
    [[ -n "${x1}" ]]
    [[ -n "${x2}" ]]
    [[ -z "${x3}" ]]
}

function _incr_and_get_sequence_number_from_file()
{
    (
        flock -w1 10 || exit 1;
        read t <${1}; echo -n $((t + 1)) | tee ${1}
    ) 10>${2}
}

function _find_user_dirs()
{
    blacklisted_email_domains=
    while getopts ":f:" o; do
        case $o in 
        f)
            blacklisted_email_domains=${OPTARG};;
        esac
    done
    shift $(( OPTIND - 1 ))
    
    declare -r users_dir=$(realpath ${1})
    [[ -d ${users_dir} ]]
    [[ -z ${blacklisted_email_domains} ]] || [[ -f ${blacklisted_email_domains} ]]
    
    for u in $(ls -1 ${users_dir}); do
        d=${users_dir}/${u}
        if [[ ! -d ${d} ]]; then
            continue
        fi
        if ! _validate_username ${u} username_local_part username_domain; then
            logger -s -p local7.warn -t ${0} -- \
                "${FUNCNAME}: user [${u}] has an invalid username"
            continue
        fi
        if [[ -n ${blacklisted_email_domains} ]] && \
                grep -qxF -e "${username_domain}" ${blacklisted_email_domains}; 
        then
            logger -s -p local7.info -t ${0} -- \
                "${FUNCNAME}: user [${u}] belongs to a blacklisted email domain [${username_domain}]" 
            continue
        fi
        echo ${d}
    done
}

