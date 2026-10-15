FROM itharbor.leapmotor.com/bms_cloud/bms-project-mysql:V1.0

WORKDIR /opt/bms

COPY target/pcb-review-1.0-SNAPSHOT.tar.gz /opt/bms/

RUN tar -zxf pcb-review-1.0-SNAPSHOT.tar.gz \
    && rm -f pcb-review-1.0-SNAPSHOT.tar.gz \
    && mv pcb-review-1.0-SNAPSHOT/* ./ \
    && rm -rf pcb-review-1.0-SNAPSHOT \
    && chmod +x ./startup.sh \
    && test -f ./pcb-review-1.0-SNAPSHOT.jar \
    && test -f ./application.yml \
    && test -f ./startup.sh

EXPOSE 8080

ENTRYPOINT ["./startup.sh", "start"]