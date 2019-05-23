all:
	docker-compose build sqan
	docker-compose up -d --force-recreate sqan
	docker exec sqan find /sqan/ -name '*.apk' | while read line ; do \
		docker cp sqan:$$line . ; \
	done
	docker-compose down || true
