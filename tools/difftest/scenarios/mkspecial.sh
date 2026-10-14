#!/bin/bash
cd tree
mkdir -p uni
printf 'hello world\n' > "$(printf 'uni/h\xc3\xa9llo w\xc3\xb6rld.txt')"
printf 'zz\n' > "$(printf 'uni/\xe6\x97\xa5\xe6\x9c\xac.txt')"
ln -s small link
chmod 4755 sub/prog.exe; chmod 600 s.txt
mkfifo fifo
touch -h -d '2019-03-04 05:06:07' uni/* link
touch -d '2019-03-04 05:06:07' uni
