#!/bin/sh

if [ "$#" -ne 2 ]; then
	echo "Wrong number of parameters"
    echo "Usage: transfer_files.sh IP_address password"
    exit
fi

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"

echo "Transferring files to target device: $1"

sshpass -p "$2" scp -r "$SCRIPT_DIR"/../bin keonn@$1:/home/keonn/keonn_embedded

