#!/bin/bash

declare -r -i default_space_limit_in_bytes=$(( 1024 * 1024 * 1024 ))
declare -r -i default_space_hard_limit_percent_cap=20
declare -r -i default_space_hard_limit_in_bytes=$(( (default_space_limit_in_bytes * (100 + default_space_hard_limit_percent_cap)) / 100 ))
declare -r -i max_space_limit_in_bytes=$(( 8 * default_space_limit_in_bytes ))

declare -r -i default_files_limit=$(( 512 * 1024 ))
declare -r -i default_files_hard_limit_percent_cap=20
declare -r -i default_files_hard_limit=$(( (default_files_limit * (100 + default_files_hard_limit_percent_cap)) / 100 ))
declare -r -i max_files_limit=$(( 10 * default_files_limit ))
