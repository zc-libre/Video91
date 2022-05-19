#!/bin/bash
tar -zxvf package.tgz
tar -zxvf video-spider-1.0.0-app.tar.gz
docker stop app
docker rm app
cd /root/app/
rm -rf maven* classes arc* generated-sources lib
cd video-spider-1.0.0/bin
sudo docker rmi app:1.0
docker build -t app:1.0 .
docker run -it --name app \
 -p 9870:9870 \
 -v /root/app/app/config:/libre/app/config \
 -v /root/app/app/logs:/libre/logs \
 -v /root/video/:/libre/video/ \
 -d app:1.0
