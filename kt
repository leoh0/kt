#!/bin/bash

readonly PROGNAME=$(basename $0)

default_since="1s"
default_line_buffered=""
default_colored_output="line"
default_timestamps=""
default_jq_selector=""
default_skip_colors="7,8"

line_buffered="${default_line_buffered}"
colored_output="${default_colored_output}"
timestamps="${default_timestamps}"
skip_colors="${default_skip_colors}"

since="${default_since}"
version="leoh0/1.0.1"

multi_select=""
full_log=""

usage="${PROGNAME} [/-m/-l] [-t] [-s] [-b] [-j] [-k] [-v] [-h]
description:
    select a pod and tailing multiple k8s pods logs at the same time

where:
    -m, --multiselect    Multiselect servers, but it can not be use with auto tailing.
    -l, --fulllog        Show full log about selected one container.

    -t, --context        The k8s context. ex. int1-context. Relies on ~/.kube/config for the contexts.
    -s, --since          Only return logs newer than a relative duration like 5s, 2m, or 3h. Defaults to 1s.
    -b, --line-buffered  This flags indicates to use line-buffered. Defaults to false.
    -j, --jq             If your output is json - use this jq-selector to parse it.
                         example: --jq \".logger + \\\" \\\" + .message\"
    -k, --colored-output Use colored output (pod|line|false).
                         pod = only color podname, line = color entire line, false = don't use any colors.
                         Defaults to line.
    -z, --skip-colors    Comma-separated list of colors to not use in output
                         If you have green foreground on black, this will skip dark grey and some greens -z 2,8,10
                         Defaults to: 7,8
        --timestamps     Show timestamps for each log line
    -v, --version        Prints the kt version
    -h, --help           Show this help text

examples:
    ${PROGNAME} # select a pod with fzf and auto tail it
    ${PROGNAME} -m # select multiple pods with fzf and tail it
    ${PROGNAME} -l # select a pod with fzf and get a full log"

if [ "$#" -ne 0 ]; then
    while [ "$#" -gt 0 ]
    do
        case "$1" in
        -h|--help)
            echo "$usage"
            exit 0
            ;;
        -v|--version)
            echo "$version"
            exit 0
            ;;
        -m|--multiselect)
            multi_select="true"
            ;;
        -l|--logfull)
            full_log="true"
            ;;
        -t|--context)
            context="$2"
            ;;
        -s|--since)
            if [ -z "$2" ]; then
                since="${default_since}"
            else
                since="$2"
            fi
            ;;
        -b|--line-buffered)
            if [ "$2" = "true" ]; then
                line_buffered="| grep - --line-buffered"
            fi
            ;;
        -k|--colored-output)
            if [ -z "$2" ]; then
                colored_output="${default_colored_output}"
            else
                colored_output="$2"
            fi
            ;;
        -j|--jq)
            if [ -z "$2" ]; then
                jq_selector="${default_jq_selector}"
            else
                jq_selector="$2"
            fi
            ;;
        -z|--skip-colors)
            if [ -z "$2" ]; then
                skip_colors="${default_skip_colors}"
            else
                skip_colors="$2"
            fi
            ;;
        --timestamps)
            if [ "$2" = "false" ]; then
                timestamps="$1=$2"
            else
                timestamps="$1"
            fi
            ;;
        --)
            break
            ;;
        -*)
            echo "Invalid option '$1'. Use --help to see the valid options" >&2
            exit 1
            ;;
        # an option argument, continue
        *)  ;;
        esac
        shift
    done
fi

chkcommand() {
  command -v $1 >/dev/null 2>&1 || { echo >&2 "Plz install $1 first. Aborting."; return 1; }
}
chkcommand fzf || exit 1
chkcommand kubectl || exit 1

# Join function that supports a multi-character seperator (copied from http://stackoverflow.com/a/23673883/398441)
function join() {
    # $1 is return variable name
    # $2 is sep
    # $3... are the elements to join
    local retname=$1 sep=$2 ret=$3
    shift 3 || shift $(($#))
    printf -v "$retname" "%s" "$ret${@/#/$sep}"
}

function next_col {
    potential_col=$(($1+1))
    [[ $skip_colors =~ (^|,)$potential_col($|,) ]] && echo `next_col $potential_col` || echo $potential_col
}

# Function that kills all kubectl processes that are started by kt in the background
# It does this by reading from the "pid_temp_file" that contains the PID of all kubectl processes
function kill_kubectl_processes {
    if [[ "$tailpid" != '' ]]; then
        pid_cmd=$(ps -p "$tailpid" -o command=)
        if [[ $pid_cmd == *tail* ]]; then
            kill "$tailpid" 2>/dev/null || kill -2 "$tailpid" 2>/dev/null || kill -9 "$tailpid" 2>/dev/null
        fi
        rm ${tailpid_temp_file} 2>/dev/null
    fi

    if [[ "$pid_temp_file" != '' ]]; then
        while read p; do
            # In order to not kill the wrong processes (for example if a kubectl process died and another process replaced its PID)
            # we check that the process name contains "kt". It's not bulletproof since another kt process
            # might have picked up the old PID but this is deemed unlikley.
            pid_cmd=$(ps -p "$p" -o command=)
            if [[ $pid_cmd == *${PROGNAME}* ]]; then
                # Try killing gently first and then progress to more firm alternatives if this fails
                  kill "$p" 2>/dev/null || kill -2 "$p" 2>/dev/null || kill -9 "$p" 2>/dev/null
            fi
        done < "$pid_temp_file"
        rm ${pid_temp_file} 2>/dev/null
    fi
}

# Invoke the "kill_kubectl_processes" function when the script is stopped (including ctrl+c)
# Note that "INT" is not used because if, for example, kubectl cannot find a container 
# (for example when running "kt something -c non_matching") we still need to delete
# the temporary file in these cases as well.
trap kill_kubectl_processes EXIT

if [[ "${multi_select}" != "" && "${full_log}" != "" ]]; then
    echo "You can not set -m with -f." >&2
    exit 1
fi

# Get all pods matching the input and put them in an array. If no input then all pods are matched.
if [[ "${multi_select}" == "" && "${full_log}" == "" ]]; then
    matched=$(kubectl get pods --show-labels=true -o wide --all-namespaces | sed '1d' | fzf -x -e +s --reverse --bind=left:page-up,right:page-down --no-mouse | awk '{print $1":"$9}')
    matched_namespace=$(echo $matched | cut -d':' -f1)
    matched_label=$(echo $matched | cut -d':' -f2)
fi

matching_pods_string=''
last_matching_pods_string=''
tailpid=''
tailpid_temp_file=''

color_end=$(tput sgr0)

# Allows for more colors, this is useful if one tails a lot pods
if [ ${colored_output} != "false" ]; then
    export TERM=xterm-256color
fi

while true ; do
    if [[ "${full_log}" != "" ]]; then
        matching_pods=(`kubectl get pods -o wide --all-namespaces | sed '1d' | fzf -x -e +s --reverse --bind=left:page-up,right:page-down --no-mouse | awk '{print $1":"$2}'`)
    else
        if [[ "${multi_select}" != "" ]]; then
            matching_pods=(`kubectl get pods -o wide --all-namespaces | sed '1d' | fzf -x -m -e +s --reverse --bind=left:page-up,right:page-down --no-mouse | awk '{print $1":"$2}'`)
        else
            matching_pods=(`kubectl get pods -n ${matched_namespace} -l ${matched_label} | sed '1d' | awk "{print \"${matched_namespace}:\"\\$1\":\"\\$2\":\"\\$3}"`)
        fi
    fi
    matching_pods_size=${#matching_pods[@]}

    if [ ${matching_pods_size} -eq 0 ]; then
        echo "No pods exists that matches ${pod}"
        if [[ "${multi_select}" != "" || "${full_log}" != "" ]]; then
            sleep 5
            continue
        fi
        exit 1
    fi


    # Wrap all pod names in the "kubectl logs <name> -f" command
    display_names_preview=()
    i=0
    color_index=0
    matching_pods_string=''
    command_to_tail=''
    logs_commands=()

    PID=`echo $$` # Get the PID of this process
    pid_temp_file="/tmp/kt.${PID}" # Use the PID to create a temp file
    touch ${pid_temp_file} # Initiate the temp file

    for line in ${matching_pods[@]}; do
        matching_pods_string=$(echo "$matching_pods_string$line")
        namespace=$(echo $line | cut -d':' -f1)
        pod=$(echo $line | cut -d':' -f2)
        pod_status=$(echo $line | cut -d':' -f4)
        pod_containers=($(kubectl get pod ${pod} --context=${context} --output=jsonpath='{.spec.containers[*].name}' --namespace=${namespace} | xargs -n1))

        for container in ${pod_containers[@]}; do

            if [[ "${full_log}" != "" ]]; then
                color_start=""
                color_end=""
            else
                if [ ${colored_output} == "false" ] || [ ${matching_pods_size} -eq 1 -a ${#pod_containers[@]} -eq 1 ]; then
                    color_start=$(tput sgr0)
                else
                    color_index=`next_col $color_index`
                    color_start=$(tput setaf $color_index)
                fi
            fi

            if [ ${#pod_containers[@]} -eq 1 ]; then
                display_name="${pod}"
            else
                display_name="${pod} ${container}"
            fi
            display_names_preview+=("${color_start}${display_name} ${pod_status}${color_end}")

            if [ ${colored_output} == "pod" ]; then
                colored_line="${color_start}[${display_name}]${color_end} \$line"
            else
                colored_line="${color_start}[${display_name}] \$line ${color_end}"
            fi

            if [[ "${full_log}" != "" ]]; then
                kubectl_cmd="kubectl --context=${context} logs ${pod} ${container} --namespace=${namespace}"
                logs_commands+=("${kubectl_cmd} ${timestamps}");
            else
                kubectl_cmd="kubectl --context=${context} logs ${pod} ${container} -f --since=${since} --namespace=${namespace}"
                colorify_lines_cmd="while read line; do echo \"$colored_line\" | tail -n +1; done"
                capture_pid_to_file="& echo "'$!'" >> ${pid_temp_file}"
                if [ "z" == "z$jq_selector" ]; then
                    logs_commands+=("${kubectl_cmd} ${timestamps} | ${colorify_lines_cmd} ${capture_pid_to_file}");
                else
                    logs_commands+=("${kubectl_cmd} | jq --unbuffered -r -R --stream '. | fromjson? | $jq_selector ' | ${colorify_lines_cmd} ${capture_pid_to_file}");
                fi
            fi

            # There are only 11 usable colors
            i=$(( ($i+1)%13 ))
        done
    done

    # Join all log commands into one string seperated by " & "
    join command_to_tail " & " "${logs_commands[@]}"

    if [[ "${last_matching_pods_string}" != "${matching_pods_string}" ]]; then
        if [[ "$tailpid" != '' ]]; then
            kill_kubectl_processes
        fi
        last_matching_pods_string="${matching_pods_string}"

        # Preview pod colors
        echo "Will tail ${i} logs..."
        for preview in "${display_names_preview[@]}"; do
            echo "$preview"
        done

        if [[ "${full_log}" != "" ]]; then
            # Aggreate all logs and print to stdout
            cat <( eval "${command_to_tail}" ) $line_buffered | fzf -x -e +s --reverse --bind=left:page-up,right:page-down --no-mouse
            exit 0
        else
            # Aggreate all logs and print to stdout
            /usr/bin/tail +1f <( eval "${command_to_tail}" ) $line_buffered &
        fi
        tailpid=$(echo $!)
        tailpid_temp_file="/tmp/kt.${tailpid}" # Use the PID to create a temp file
        touch ${tailpid_temp_file} # Initiate the temp file
    fi

    sleep 5

    if [[ "${multi_select}" != "" || "${full_log}" != "" ]]; then
        wait
    fi
done
