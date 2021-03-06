/*-
 * ========================LICENSE_START=================================
 * Smooks Core
 * %%
 * Copyright (C) 2020 Smooks
 * %%
 * Licensed under the terms of the Apache License Version 2.0, or
 * the GNU Lesser General Public License version 3.0 or later.
 * 
 * SPDX-License-Identifier: Apache-2.0 OR LGPL-3.0-or-later
 * 
 * ======================================================================
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * ======================================================================
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * =========================LICENSE_END==================================
 */
package org.smooks.delivery;

import org.smooks.SmooksException;
import org.smooks.StreamFilterType;
import org.smooks.cdr.*;
import org.smooks.container.ApplicationContext;
import org.smooks.delivery.dom.DOMContentDeliveryConfig;
import org.smooks.delivery.sax.SAXContentDeliveryConfig;
import org.smooks.dtd.DTDStore;
import org.smooks.dtd.DTDStore.DTDObjectContainer;
import org.smooks.event.types.ConfigBuilderEvent;
import org.smooks.profile.ProfileSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.Map.Entry;

/**
 * Content delivery configuration builder.
 * @author tfennelly
 */
public class ContentDeliveryConfigBuilder {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(ContentDeliveryConfigBuilder.class);
	/**
	 * Context key for the table of loaded ContentDeliveryConfig instances.
	 */
	private static final String DELIVERY_CONFIG_TABLE_CTX_KEY = ContentDeliveryConfig.class.getName() + "#configTable";
	/**
	 * Profile set.
	 */
	private ProfileSet profileSet;
	/**
	 * Container context.
	 */
	private ApplicationContext applicationContext;
    /**
	 * XML selector content spec definition prefix
	 */
	private static final String ELCSPEC_PREFIX = "elcspec:";
    /**
     * An unsorted list of SmooksResourceConfiguration.
     */
    private List<SmooksResourceConfiguration> resourceConfigsList = new ArrayList<SmooksResourceConfiguration>();
    /**
	 * Table (by element) of sorted SmooksResourceConfiguration instances keyed by selector value. Each table entry
	 * contains a List of SmooksResourceConfiguration instances.
	 */
	private LinkedHashMap<String, List<SmooksResourceConfiguration>> resourceConfigTable = new LinkedHashMap<String, List<SmooksResourceConfiguration>>();
    /**
     * Visitor Config.
     */
    private VisitorConfigMap visitorConfig;
    /**
     * Config builder events list.
     */
    private List<ConfigBuilderEvent> configBuilderEvents = new ArrayList<ConfigBuilderEvent>();

    /**
	 * DTD for the associated device.
	 */
	private DTDObjectContainer dtd;

    /**
	 * Private (hidden) constructor.
     * @param profileSet Profile set.
	 * @param applicationContext Container context.
	 */
	private ContentDeliveryConfigBuilder(ProfileSet profileSet, ApplicationContext applicationContext) {
		this.profileSet = profileSet;
		this.applicationContext = applicationContext;
        visitorConfig = new VisitorConfigMap(applicationContext);
        visitorConfig.setConfigBuilderEvents(configBuilderEvents);
    }

	/**
	 * Get the ContentDeliveryConfig instance for the specified profile set.
	 * @param profileSet The profile set with which this delivery config is associated.
	 * @param applicationContext Application context.
     * @param extendedVisitorConfigMap Preconfigured/extended Visitor Configuration Map.
     * @return The ContentDeliveryConfig instance for the named table.
	 */
	public static ContentDeliveryConfig getConfig(ProfileSet profileSet, ApplicationContext applicationContext, VisitorConfigMap extendedVisitorConfigMap) {
		ContentDeliveryConfig config;
		LinkedHashMap<String, ContentDeliveryConfig> configTable;

		if(profileSet == null) {
			throw new IllegalArgumentException("null 'profileSet' arg passed in method call.");
		} else if(applicationContext == null) {
			throw new IllegalArgumentException("null 'applicationContext' arg passed in method call.");
		}

		// Get the delivery config config from container context.
        configTable = getDeliveryConfigTable(applicationContext);
        if(configTable == null) {
            synchronized(ContentDeliveryConfigBuilder.class) {
                // Try again, just in case we have 1+ threads firing...
                configTable = getDeliveryConfigTable(applicationContext);
                if(configTable == null) {
                    configTable = new LinkedHashMap<String, ContentDeliveryConfig>();
                    applicationContext.setAttribute(DELIVERY_CONFIG_TABLE_CTX_KEY, configTable);
                }
            }
        }
		// Get the delivery config instance for the base profile...
		config = configTable.get(profileSet.getBaseProfile());
		if(config == null) {
            synchronized(ContentDeliveryConfigBuilder.class) {
                // Try again, just in case we have 1+ threads firing on the same profile...
                config = configTable.get(profileSet.getBaseProfile());
                if(config == null) {
                    ContentDeliveryConfigBuilder configBuilder = new ContentDeliveryConfigBuilder(profileSet, applicationContext);
                    configBuilder.load();
                    config = configBuilder.createConfig(extendedVisitorConfigMap);
                    configTable.put(profileSet.getBaseProfile(), config);
                }
            }
        }

		return config;
	}

    private ContentDeliveryConfig createConfig(VisitorConfigMap extendedVisitorConfigMap) {
        boolean sortVisitors = ParameterAccessor.getBoolParameter(ContentDeliveryConfig.SMOOKS_VISITORS_SORT, true, resourceConfigTable);
        StreamFilterType filterType;

        visitorConfig.addAll(extendedVisitorConfigMap);

        filterType = getStreamFilterType();
        configBuilderEvents.add(new ConfigBuilderEvent("SAX/DOM support characteristics of the Resource Configuration map:\n" + getResourceFilterCharacteristics()));
        configBuilderEvents.add(new ConfigBuilderEvent("Using Stream Filter Type: " + filterType));

        if(filterType == StreamFilterType.DOM) {
            DOMContentDeliveryConfig domConfig = new DOMContentDeliveryConfig();

            LOGGER.debug("Using the DOM Stream Filter.");
            domConfig.setAssemblyVisitBefores(visitorConfig.getDomAssemblyVisitBefores());
            domConfig.setAssemblyVisitAfters(visitorConfig.getDomAssemblyVisitAfters());
            domConfig.setProcessingVisitBefores(visitorConfig.getDomProcessingVisitBefores());
            domConfig.setProcessingVisitAfters(visitorConfig.getDomProcessingVisitAfters());
            domConfig.setSerializationVisitors(visitorConfig.getDomSerializationVisitors());
            domConfig.setVisitCleanables(visitorConfig.getVisitCleanables());

            domConfig.setApplicationContext(applicationContext);
            domConfig.setSmooksResourceConfigurations(resourceConfigTable);
            domConfig.setDtd(dtd);
            domConfig.getConfigBuilderEvents().addAll(configBuilderEvents);

            if(sortVisitors) {
                domConfig.sort();
            }

            domConfig.addToExecutionLifecycleSets();
            domConfig.initializeXMLReaderPool();
            domConfig.configureFilterBypass();

            // Tell all interested listeners that the config builder for the profile has now been created.
            fireEvent(ContentDeliveryConfigBuilderLifecycleEvent.CONFIG_BUILDER_CREATED);

            return domConfig;
        } else {
            SAXContentDeliveryConfig saxConfig = new SAXContentDeliveryConfig();

            LOGGER.debug("Using the SAX Stream Filter.");
            saxConfig.setVisitBefores(visitorConfig.getSaxVisitBefores());
            saxConfig.setVisitAfters(visitorConfig.getSaxVisitAfters());
            saxConfig.setVisitCleanables(visitorConfig.getVisitCleanables());

            saxConfig.setApplicationContext(applicationContext);
            saxConfig.setSmooksResourceConfigurations(resourceConfigTable);
            saxConfig.setDtd(dtd);
            saxConfig.getConfigBuilderEvents().addAll(configBuilderEvents);

            saxConfig.optimizeConfig();
            saxConfig.assertSelectorsNotAccessingText();

            if(sortVisitors) {
                saxConfig.sort();
            }

            saxConfig.addToExecutionLifecycleSets();
            saxConfig.initializeXMLReaderPool();

            saxConfig.addIndexCounters();

            // Tell all interested listeners that the config builder for the profile has now been created.
            fireEvent(ContentDeliveryConfigBuilderLifecycleEvent.CONFIG_BUILDER_CREATED);

            return saxConfig;
        }
    }

    private StreamFilterType getStreamFilterType() {
        StreamFilterType filterType;

        if(LOGGER.isDebugEnabled()) {
            LOGGER.debug("SAX/DOM support characteristics of the Resource Configuration map:\n" + getResourceFilterCharacteristics());
        }

        String filterTypeParam = ParameterAccessor.getStringParameter(Filter.STREAM_FILTER_TYPE, resourceConfigTable);

        if(visitorConfig.getSaxVisitorCount() == visitorConfig.getVisitorCount() && visitorConfig.getDomVisitorCount() == visitorConfig.getVisitorCount()) {

            if(filterTypeParam == null) {
                filterType = StreamFilterType.SAX;
                LOGGER.debug("All configured XML Element Content Handler resource configurations can be " +
                        "applied using the SAX or DOM Stream Filter.  Defaulting to " + filterType + " Filter.  Set '" + ParameterAccessor.GLOBAL_PARAMETERS + ":"
                        + Filter.STREAM_FILTER_TYPE + "'.");
                LOGGER.debug("You can explicitly select the Filter type as follows:\n" +
                        "\t\t<resource-config selector=\"" + ParameterAccessor.GLOBAL_PARAMETERS + "\">\n" +
                        "\t\t\t<param name=\"" + Filter.STREAM_FILTER_TYPE + "\">SAX/DOM</param>\n" +
                        "\t\t</resource-config>");
            } else if(filterTypeParam.equalsIgnoreCase(StreamFilterType.DOM.name())) {
                filterType = StreamFilterType.DOM;
            } else if(filterTypeParam.equalsIgnoreCase(StreamFilterType.SAX.name())) {
                filterType = StreamFilterType.SAX;
            } else {
                throw new SmooksException("Invalid '" + Filter.STREAM_FILTER_TYPE + "' configuration parameter value of '" + filterTypeParam + "'.  Must be 'SAX' or 'DOM'.");
            }
        } else if(visitorConfig.getDomVisitorCount() == visitorConfig.getVisitorCount()) {
            filterType = StreamFilterType.DOM;
        } else if(visitorConfig.getSaxVisitorCount() == visitorConfig.getVisitorCount()) {
            filterType = StreamFilterType.SAX;
        } else {
            throw new SmooksException("Ambiguous Resource Configuration set.  All Element Content Handlers must support processing on the SAX and/or DOM Filter:\n" + getResourceFilterCharacteristics());
        }

        // If the filter type has been configured, we make sure the selected filter matches...
        if(filterTypeParam != null) {
            if(!filterTypeParam.equalsIgnoreCase(filterType.name())) {
                throw new SmooksException("The configured Filter ('" + filterTypeParam + "') cannot be used with the specified set of Smooks visitors.  The '" + filterType + "' Filter is the only filter that can be used for this set of Visitors.  Turn on Debug logging for more information.");
            }
        }

        return filterType;
    }

    /**
     * Logging support function.
     * @return Verbose characteristics string.
     */
    private String getResourceFilterCharacteristics() {
        StringBuffer stringBuf = new StringBuffer();
        List<ContentHandler> printedHandlers = new ArrayList<ContentHandler>();

        stringBuf.append("\t\tDOM   SAX    Resource  ('x' equals supported)\n");
        stringBuf.append("\t\t---------------------------------------------------------------------\n");

        printHandlerCharacteristics(visitorConfig.getDomAssemblyVisitBefores(), stringBuf, printedHandlers);
        printHandlerCharacteristics(visitorConfig.getDomAssemblyVisitAfters(), stringBuf, printedHandlers);
        printHandlerCharacteristics(visitorConfig.getDomProcessingVisitBefores(), stringBuf, printedHandlers);
        printHandlerCharacteristics(visitorConfig.getDomProcessingVisitAfters(), stringBuf, printedHandlers);
        printHandlerCharacteristics(visitorConfig.getDomSerializationVisitors(), stringBuf, printedHandlers);
        printHandlerCharacteristics(visitorConfig.getSaxVisitBefores(), stringBuf, printedHandlers);
        printHandlerCharacteristics(visitorConfig.getSaxVisitAfters(), stringBuf, printedHandlers);

        stringBuf.append("\n\n");

        return stringBuf.toString();
    }

    private <U extends ContentHandler> void printHandlerCharacteristics(ContentHandlerConfigMapTable<U> table, StringBuffer stringBuf, List<ContentHandler> printedHandlers) {
        Collection<List<ContentHandlerConfigMap<U>>> map = table.getTable().values();

        for (List<ContentHandlerConfigMap<U>> mapList : map) {
            for (ContentHandlerConfigMap<U> configMap : mapList) {
                ContentHandler handler = configMap.getContentHandler();
                boolean domSupported = VisitorConfigMap.isDOMVisitor(handler);
                boolean saxSupported = VisitorConfigMap.isSAXVisitor(handler);

                if(printedHandlers.contains(handler)) {
                    continue;
                } else {
                    printedHandlers.add(handler);
                }

                stringBuf.append("\t\t ")
                         .append(domSupported ? "x" : " ")
                         .append("     ")
                         .append(saxSupported ? "x" : " ")
                         .append("     ")
                         .append(configMap.getResourceConfig())
                         .append("\n");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, ContentDeliveryConfig> getDeliveryConfigTable(ApplicationContext applicationContext) {
        return (LinkedHashMap) applicationContext.getAttribute(DELIVERY_CONFIG_TABLE_CTX_KEY);
    }

    /**
	 * Build the ContentDeliveryConfigBuilder for the specified device.
	 * <p/>
	 * Creates the buildTable instance and populates it with the ProcessingUnit matrix
	 * for the specified device.
	 */
	private void load() {
        resourceConfigsList.clear();
        resourceConfigsList.addAll(Arrays.asList(applicationContext.getStore().getSmooksResourceConfigurations(profileSet)));

		// Build and sort the resourceConfigTable table - non-transforming elements.
		buildSmooksResourceConfigurationTable(resourceConfigsList);
		sortSmooksResourceConfigurations(resourceConfigTable);

		// If there's a DTD for this device, get it and add it to the DTDStore.
		List dtdSmooksResourceConfigurations = resourceConfigTable.get("dtd");
		if(dtdSmooksResourceConfigurations != null && dtdSmooksResourceConfigurations.size() > 0) {
            SmooksResourceConfiguration dtdSmooksResourceConfiguration = (SmooksResourceConfiguration)dtdSmooksResourceConfigurations.get(0);
            byte[] dtdDataBytes = dtdSmooksResourceConfiguration.getBytes();

            if(dtdDataBytes != null) {
                DTDStore.addDTD(profileSet, new ByteArrayInputStream(dtdDataBytes));
                // Initialise the DTD reference for this config table.
                dtd = DTDStore.getDTDObject(profileSet);
            } else {
                LOGGER.error("DTD resource [" + dtdSmooksResourceConfiguration.getResource() + "] not found in classpath.");
            }
		}

		// Expand the SmooksResourceConfiguration table and resort
		expandSmooksResourceConfigurationTable();
		sortSmooksResourceConfigurations(resourceConfigTable);

        // Extract the ContentDeliveryUnits and build the tables
        extractContentHandlers();

        // Tell all interested listeners that all the handlers have now been created.
        fireEvent(ContentDeliveryConfigBuilderLifecycleEvent.HANDLERS_CREATED);

        if(LOGGER.isDebugEnabled()) {
            logResourceConfig();
        }
	}

    /**
	 * Print a debug log of the resource configurations for the associated profile.
	 */
	private void logResourceConfig() {
		LOGGER.debug("==================================================================================================");
		LOGGER.debug("Resource configuration (sorted) for profile [" + profileSet.getBaseProfile() + "].  Sub Profiles: [" + profileSet + "]");
		Iterator configurations = resourceConfigTable.entrySet().iterator();
		int i = 0;

		while(configurations.hasNext()) {
			Map.Entry entry = (Entry) configurations.next();
			List resources = (List)entry.getValue();

			LOGGER.debug(i + ") " + entry.getKey());
			for (int ii = 0; ii < resources.size(); ii++) {
				LOGGER.debug("\t(" + ii + ") " + resources.get(ii));
			}
		}
		LOGGER.debug("==================================================================================================");
	}

	/**
	 * Build the basic SmooksResourceConfiguration table from the list.
	 * @param resourceConfigsList List of SmooksResourceConfigurations.
	 */
	private void buildSmooksResourceConfigurationTable(List resourceConfigsList) {
    for (final Object resourceConfig : resourceConfigsList)
    {
      addResourceConfiguration((SmooksResourceConfiguration) resourceConfig);
    }
	}

    /**
     * Add the supplied resource configuration to this configuration's main
     * resource configuration list.
     * @param config The configuration to be added.
     */
    private void addResourceConfiguration(SmooksResourceConfiguration config) {
        String target = config.getSelector();

        // If it's contextual, it's targeting an XML element...
        if(config.isSelectorContextual()) {
            target = config.getTargetElement();
        }

        addResourceConfiguration(target, config);
    }

    /**
     * Add the config for the specified element.
     * @param element The element to which the config is to be added.
     * @param resourceConfiguration The Object to be added.
     */
    @SuppressWarnings("unchecked")
    private void addResourceConfiguration(String element, SmooksResourceConfiguration resourceConfiguration) {
        // Add it to the unsorted list...
        if(!resourceConfigsList.contains(resourceConfiguration)) {
            resourceConfigsList.add(resourceConfiguration);
        }

        // Add it to the sorted resourceConfigTable...
        List elementConfigList = resourceConfigTable.get(element);
        if(elementConfigList == null) {
            elementConfigList = new Vector();
            resourceConfigTable.put(element, elementConfigList);
        }
        if(!elementConfigList.contains(resourceConfiguration)) {
            elementConfigList.add(resourceConfiguration);
        }
    }

    /**
	 * Expand the SmooksResourceConfiguration table.
	 * <p/>
	 * Expand the XmlDef entries to the target elements etc.
	 */
	private void expandSmooksResourceConfigurationTable() {
		class ExpansionSmooksResourceConfigurationStrategy implements SmooksResourceConfigurationStrategy {
			private ExpansionSmooksResourceConfigurationStrategy() {
			}
			public void applyStrategy(String elementName, SmooksResourceConfiguration resourceConfig) {
				// Expand XmlDef entries.
				if(resourceConfig.isXmlDef()) {
					String[] elements = getDTDElements(resourceConfig.getSelector().substring(SmooksResourceConfiguration.XML_DEF_PREFIX.length()));
          for (final String element : elements)
          {
            addResourceConfiguration(element, resourceConfig);
          }
				}

				// Add code to expand other expandable entry types here.
			}
		}
		SmooksResourceConfigurationTableIterator tableIterator = new SmooksResourceConfigurationTableIterator(new ExpansionSmooksResourceConfigurationStrategy());
		tableIterator.iterate();
	}

    /**
	 * Iterate over the table smooks-resource instances and sort the SmooksResourceConfigurations
	 * on each element.  Ordered by specificity.
	 */
	@SuppressWarnings("unchecked")
  private void sortSmooksResourceConfigurations(Map<String, List<SmooksResourceConfiguration>> table) {
        Parameter sortParam = ParameterAccessor.getParameter("sort.resources", table);
        if(sortParam != null && sortParam.getValue().trim().equalsIgnoreCase("true")) {
            if(!table.isEmpty()) {

              for (final Object o : table.entrySet())
              {
                Entry entry = (Entry) o;
                List markupElSmooksResourceConfigurations = (List) entry.getValue();
                SmooksResourceConfiguration[] resourceConfigs = (SmooksResourceConfiguration[]) markupElSmooksResourceConfigurations
                    .toArray(new SmooksResourceConfiguration[0]);
                SmooksResourceConfigurationSortComparator sortComparator = new SmooksResourceConfigurationSortComparator(profileSet);

                Arrays.sort(resourceConfigs, sortComparator);
                entry.setValue(new Vector(Arrays.asList(resourceConfigs)));
              }
            }
        }
    }

    /**
	 * Extract the ContentHandler instances from the SmooksResourceConfiguration table and add them to
	 * their respective tables.
	 */
	private void extractContentHandlers() {
		ContentHandlerExtractionStrategy cduStrategy = new ContentHandlerExtractionStrategy(applicationContext);
		SmooksResourceConfigurationTableIterator tableIterator = new SmooksResourceConfigurationTableIterator(cduStrategy);

        tableIterator.iterate();
    }

    /**
	 * Get the DTD elements for specific device context.
	 * @param string DTD spec string e.g. "elcspec:empty"
	 * @return List of element names.
	 */
	private String[] getDTDElements(String string) {
		String tmpString = string;

		if(tmpString.startsWith(ELCSPEC_PREFIX)) {
			tmpString = tmpString.substring(ELCSPEC_PREFIX.length());
			if(tmpString.equals("empty")) {
				return dtd.getEmptyElements();
			} else if(tmpString.equals("not-empty")) {
				return dtd.getNonEmptyElements();
			} else if(tmpString.equals("any")) {
				return dtd.getAnyElements();
			} else if(tmpString.equals("not-any")) {
				return dtd.getNonAnyElements();
			} else if(tmpString.equals("mixed")) {
				return dtd.getMixedElements();
			} else if(tmpString.equals("not-mixed")) {
				return dtd.getNonMixedElements();
			} else if(tmpString.equals("pcdata")) {
				return dtd.getPCDataElements();
			} else if(tmpString.equals("not-pcdata")) {
				return dtd.getNonPCDataElements();
			}
		}

		throw new IllegalStateException("Unsupported DTD spec definition [" + string + "]");
	}

    private void logExecutionEvent(SmooksResourceConfiguration resourceConfig, String message) {
        configBuilderEvents.add(new ConfigBuilderEvent(resourceConfig, message));
    }

    private void fireEvent(ContentDeliveryConfigBuilderLifecycleEvent event) {
        List<Object> initializedObjects = applicationContext.getStore().getInitializedObjects();

        for(Object object : initializedObjects) {
            if(object instanceof ContentDeliveryConfigBuilderLifecycleListener) {
                ((ContentDeliveryConfigBuilderLifecycleListener) object).handle(event);
            }
        }
    }

    /**
	 * ContentHandler extraction strategy.
	 * @author tfennelly
	 */
	private final class ContentHandlerExtractionStrategy implements SmooksResourceConfigurationStrategy {

        private SmooksResourceConfigurationStore store;

        private ContentHandlerExtractionStrategy(ApplicationContext applicationContext) {
            store = applicationContext.getStore();
        }

        public void applyStrategy(String elementName, SmooksResourceConfiguration resourceConfig) {
            applyCDUStrategy(resourceConfig);
        }

        private boolean applyCDUStrategy(SmooksResourceConfiguration resourceConfig) {
			ContentHandlerFactory creator;

			// Try it as a Java class before trying anything else.  This is to
			// accomodate specification of the class in the standard
			// Java form e.g. java.lang.String Vs java/lang/String.class
            if(resourceConfig.isJavaContentHandler()) {
    			try {
    				creator = store.getContentHandlerFactory("class");
    				if(addCDU(resourceConfig, creator)) {
    					// Job done - it's a CDU and we've added it!
    					return true;
    				}
    			} catch (UnsupportedContentHandlerTypeException e) {
    				throw new IllegalStateException("No ContentHandlerFactory configured (IoC) for type 'class' (Java).");
    			}
            }

            // Get the resource type and "try" creating a ContentHandlerFactory for that resource
            // type.
            String restype = resourceConfig.getResourceType();
            creator = tryCreateCreator(restype);

            // If we have a creator but it's the JavaContentHandlerFactory we ignore it because
            // we know the class in question does not implement ContentHandler.  We know because
            // we tried this above.
            if(creator != null) {
                if(!(creator instanceof JavaContentHandlerFactory)) {
                    return addCDU(resourceConfig, creator);
                }
            } else {
				// Just ignore it - something else will use it (hopefully)
                if(restype != null) {
                    logExecutionEvent(resourceConfig, "Unable to create ContentHandler class instance for resource.  " +
                            "This is probably because there's no " + ContentHandlerFactory.class.getSimpleName()  + " implementation for resource " +
                            "type '" + restype + "' available on the classpath.");
                }
            }

            return false;
        }

        /**
         * Try create the CDU creator for the specified resource type.
         * <p/>
         * Return null if unsuccessful i.e. no exceptions.
         * @param restype The resource type.
         * @return The appropriate CDU creator instance, or null if there is none.
         */
        private ContentHandlerFactory tryCreateCreator(String restype) {
			ContentHandlerFactory creator;

			try {
				if(restype == null || restype.trim().equals("")) {
					LOGGER.debug("Request to attempt ContentHandlerFactory creation based on a null/empty resource type.");
					return null;
				}
				creator = store.getContentHandlerFactory(restype);
			} catch (UnsupportedContentHandlerTypeException e) {
				return null;
			}

			return creator;
        }

        /**
		 * Add a {@link ContentHandler} for the specified element and configuration.
		 * @param resourceConfig Configuration.
		 * @param handlerFactory CDU Creator class.
		 * @return True if the CDU was added, otherwise false.
		 */
		private boolean addCDU(SmooksResourceConfiguration resourceConfig, ContentHandlerFactory handlerFactory)
    {
			Object contentHandler;

			// Create the ContentHandler.
			try {
				contentHandler = handlerFactory.create(resourceConfig);
                store.getInitializedObjects().add(contentHandler);
            } catch(SmooksConfigurationException e) {
                throw e;
            } catch(Throwable thrown) {
                String message = "ContentHandlerFactory [" + handlerFactory.getClass().getName()  + "] unable to create resource processing instance for resource [" + resourceConfig + "]. ";

                if(LOGGER.isDebugEnabled()) {
                    LOGGER.debug(message, thrown);
                } else {
                    LOGGER.debug(message + thrown.getMessage());
				}
                configBuilderEvents.add(new ConfigBuilderEvent(resourceConfig, message, thrown));

                return false;
			}

            if(contentHandler instanceof Visitor) {
                // Add the visitor.  No need to configure it as that should have been done by
                // creator...
                visitorConfig.addVisitor((Visitor) contentHandler, resourceConfig, false);
            }

            // Content delivery units are allowed to dynamically add new configurations...
            if(contentHandler instanceof ConfigurationExpander) {
                List<SmooksResourceConfiguration> additionalConfigs = ((ConfigurationExpander)contentHandler).expandConfigurations();
                if(additionalConfigs != null && !additionalConfigs.isEmpty()) {
                    if(LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Adding expansion resource configurations created by: " + resourceConfig);
                        for (SmooksResourceConfiguration additionalConfig : additionalConfigs) {
                            LOGGER.debug("\tAdding expansion resource configuration: " + additionalConfig);
                        }
                    }
                    processExpansionConfigurations(additionalConfigs);
                }
            }

            if(contentHandler instanceof VisitorAppender) {
                ((VisitorAppender)contentHandler).addVisitors(visitorConfig);
            }

            return true;
		}

        /**
         * Process the supplied expansion configurations.
         * @param additionalConfigs Expansion configs.
         */
        private void processExpansionConfigurations(List<SmooksResourceConfiguration> additionalConfigs) {
            for(final SmooksResourceConfiguration config : additionalConfigs) {
              // Try adding it as a ContentHandler instance...
                if(!applyCDUStrategy(config)) {
                    // Else just add it to the main list...
                    addResourceConfiguration(config);
                }
            }
        }

    }

    /**
	 * Iterate over the SmooksResourceConfiguration table applying the constructor
	 * supplied SmooksResourceConfigurationStrategy.
	 * @author tfennelly
	 */
	private class SmooksResourceConfigurationTableIterator {

		/**
		 * Iteration strategy.
		 */
		private SmooksResourceConfigurationStrategy strategy;

		/**
		 * Private constructor.
		 * @param strategy Strategy algorithm implementation.
		 */
		private SmooksResourceConfigurationTableIterator(SmooksResourceConfigurationStrategy strategy) {
			this.strategy = strategy;
		}

		/**
		 * Iterate over the table applying the strategy.
		 */
		private void iterate() {
      for (final SmooksResourceConfiguration smooksResourceConfiguration : resourceConfigsList)
      {
        strategy.applyStrategy(smooksResourceConfiguration.getTargetElement(), smooksResourceConfiguration);
      }
		}
	}

	/**
	 * Unitdef iteration strategy interface.
	 * @author tfennelly
	 */
	private interface SmooksResourceConfigurationStrategy {
		/**
		 * Apply the strategy algorithm.
		 * @param elementName The element name
		 * @param unitDef The SmooksResourceConfiguration
		 */
		void applyStrategy(String elementName, SmooksResourceConfiguration unitDef);
	}
}
