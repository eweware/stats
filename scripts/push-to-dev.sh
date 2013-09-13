#!/bin/bash -ex
rm -f ../src.zip
zip -r ../src.zip ../src
scp -i $OREGON ../src.zip ubuntu@dev.blahgua.com:/home/ubuntu/dev/stats/
scp -i $OREGON ../pom.xml ubuntu@dev.blahgua.com:/home/ubuntu/dev/stats/
rm -f ../src.zip
