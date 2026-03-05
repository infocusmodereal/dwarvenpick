#!/bin/bash

SEED_MARKER="${VERTICADATA}/.dwarvenpick_seeded"
CONFIG_DIR="${VERTICADATA}/config"

if [ ! -f "${CONFIG_DIR}/admintools.conf" ] && [ -f /opt/vertica/config/admintools.conf ]; then
  mkdir -p "${CONFIG_DIR}"
  /bin/cp /opt/vertica/config/admintools.conf "${CONFIG_DIR}/admintools.conf"
  chown -R dbadmin:verticadba "${CONFIG_DIR}" 2>/dev/null || true
fi

if [ -f "${SEED_MARKER}" ]; then
  CUSTOMER_COUNT="$(
    su - dbadmin -c "/opt/vertica/bin/vsql -U dbadmin -d ${DATABASE_NAME} ${VSQLPW} -t -A -c \"SELECT COUNT(*) FROM warehouse.customers;\"" 2>/dev/null || true
  )"
  CUSTOMER_COUNT="${CUSTOMER_COUNT//[[:space:]]/}"
  if [ -n "${CUSTOMER_COUNT}" ] && [ "${CUSTOMER_COUNT}" -gt 0 ] 2>/dev/null; then
    echo "Vertica seed already applied."
    return 0
  fi
  echo "Vertica seed marker present but data is missing. Re-seeding..."
fi

echo "Seeding Vertica database '${DATABASE_NAME}'..."
su - dbadmin -c "/opt/vertica/bin/vsql -v ON_ERROR_STOP=1 -U dbadmin -d ${DATABASE_NAME} ${VSQLPW} -f /vertica-seed.sql"
touch "${SEED_MARKER}"
echo "Vertica seeded."
