curl -XDELETE 'http://localhost:9200/entity_set_data_model'
curl -XDELETE 'http://localhost:9200/organizations'
curl -XDELETE 'http://localhost:9200/property_type_index'
curl -XDELETE 'http://localhost:9200/entity_type_index'
curl -XDELETE 'http://localhost:9200/association_type_index'
curl -XDELETE 'http://localhost:9200/app_index'
curl -XDELETE 'http://localhost:9200/app_type_index'
curl -XDELETE 'http://localhost:9200/securable_object_*'
elasticsearch-plugin remove analysis-phonetic && elasticsearch-plugin install analysis-phonetic
