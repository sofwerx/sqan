all:
	docker-compose build sqan
	docker-compose up -d --force-recreate sqan
	docker exec sqan find /sqan/app -name '*.apk' | while read line ; do \
		echo $$line ; \
		docker cp sqan:$$line . ; \
	done
	docker exec sqan find /sqan/testing -name '*.apk' | while read line ; do \
		echo $$line ; \
		docker cp sqan:$$line ./testing ; \
	done
	ls -l
	docker-compose down || true
