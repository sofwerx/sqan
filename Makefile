all:
	docker-compose build sqan
	docker-compose up -d --force-recreate sqan
	docker exec sqan find /sqan/ -name '*.apk' | while read line ; do \
		echo $line
		docker cp sqan:$$line . ; \
	done
	ls -l
	docker-compose down || true
