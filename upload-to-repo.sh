#!/bin/bash

while getopts 'd:l:u:p:' OPT; do
    case $OPT in
        d) DIR="$OPTARG";;
        l) URL="$OPTARG";;
	u) USERNAME="$OPTARG";;
	p) PASSWORD="$OPTARG";;
    esac
done

# Reference: http://roboojack.blogspot.in/2014/12/bulk-upload-your-local-maven-artifacts.html

if ! [ -d "$DIR" ]; then
    echo "Usage:"
    echo "       bash run.sh [-d repoRootFolder] [-l repositoryUrl] [-u username] [-p password]"
    echo ""
    echo ""
    echo "       Where..."
    echo "       repoRootFolder: The folder containing the repository tree."
    echo "                       Ensure you move the repository outside of ~/.m2 folder"
    echo "                       or whatever is configured in settings.xml"
    #echo "       repositoryId:   The repositoryId from the <server> configured for the repositoryUrl in settings.xml."
    #echo "                       Ensure that you have configured username and password in settings.xml."
    echo "       repositoryUrl:  The URL of the repository where you want to upload the files."
    exit 1
fi

cd $DIR

while read -r line ; do
    echo "Processing file $line"
    #pomLocation=$line #${line/./}
    #pomLocation=${pomLocation/jar/pom}
    #jarLocation=$line #${line/./}
    #jarLocation=${jarLocation/pom/jar}
    #echo $pomLocation
    #echo $jarLocation
    #echo uploading pom $pomLocation
    #curl -v -u $USERNAME:$PASSWORD --upload-file $pomLocation ${URL%/}/$pomLocation
    #echo uploading jar $jarLocation
    curl -v -u $USERNAME:$PASSWORD --upload-file $line ${URL%/}/$line
    #mvn deploy:deploy-file -DpomFile=$pomLocation -Dfile=$jarLocation -DrepositoryId=$2 -Durl=$3
done < <(find * -type f)
