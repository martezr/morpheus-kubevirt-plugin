#!/bin/bash


curl --request GET -k \
     --url 'https://grtmorpheus01.grt.local/api/zones?max=25&offset=0&sort=name&direction=asc&name=kubevirt' \
     --header 'accept: application/json' \
     --header 'authorization: Bearer 209aebfb-291d-4a7e-b30b-3d00daf76f60'