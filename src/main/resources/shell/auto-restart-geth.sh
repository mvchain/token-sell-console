#!/bin/bash

kill -9 `ps -ef | grep "geth --rpc"|egrep -v "grep"|awk '{print $2}'`
nohup geth --rpc --rpcaddr 127.0.0.1 --rpccorsdomain "*" --rpcapi "db,eth,net,web3,personal" > /opt/log/geth.log 2>&1 &