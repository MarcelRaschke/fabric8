/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.forge.camel.commands.project.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;

import io.fabric8.forge.camel.commands.project.model.CamelEndpointDetails;
import io.fabric8.forge.camel.commands.project.model.CamelSimpleDetails;
import org.jboss.forge.roaster._shade.org.eclipse.jdt.core.dom.Expression;
import org.jboss.forge.roaster._shade.org.eclipse.jdt.core.dom.MemberValuePair;
import org.jboss.forge.roaster._shade.org.eclipse.jdt.core.dom.NormalAnnotation;
import org.jboss.forge.roaster._shade.org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.jboss.forge.roaster.model.Annotation;
import org.jboss.forge.roaster.model.source.FieldSource;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.MethodSource;

import static io.fabric8.forge.camel.commands.project.helper.CamelCatalogHelper.endpointComponentName;

/**
 * A Camel RouteBuilder parser that uses forge and roaster.
 * <p/>
 * This implementation is higher level details, and uses the lower level parser {@link CamelJavaParserHelper}.
 */
public class RouteBuilderParser {

    public static void parseRouteBuilderEndpoints(JavaClassSource clazz, String baseDir, String fullyQualifiedFileName,
                                                  List<CamelEndpointDetails> endpoints) {
        // must be a route builder class (or from spring-boot)
        // TODO: we should look up the type hierachy if possible
        String superType = clazz.getSuperType();
        if (superType != null) {
            boolean valid = "org.apache.camel.builder.RouteBuilder".equals(superType)
                    || "org.apache.camel.spring.boot.FatJarRouter".equals(superType)
                    || "org.apache.camel.test.junit4.CamelTestSupport".equals(superType);
            if (!valid) {
                return;
            }
        }

        // look for fields which are not used in the route
        for (FieldSource<JavaClassSource> field : clazz.getFields()) {

            // is the field annotated with a Camel endpoint
            String uri = null;
            for (Annotation ann : field.getAnnotations()) {
                boolean valid = "org.apache.camel.EndpointInject".equals(ann.getQualifiedName()) || "org.apache.camel.cdi.Uri".equals(ann.getQualifiedName());
                if (valid) {
                    Expression exp = (Expression) ann.getInternal();
                    if (exp instanceof SingleMemberAnnotation) {
                        exp = ((SingleMemberAnnotation) exp).getValue();
                    } else if (exp instanceof NormalAnnotation) {
                        List values = ((NormalAnnotation) exp).values();
                        for (Object value : values) {
                            MemberValuePair pair = (MemberValuePair) value;
                            if ("uri".equals(pair.getName().toString())) {
                                exp = pair.getValue();
                                break;
                            }
                        }
                    }
                    uri = CamelJavaParserHelper.getLiteralValue(clazz, exp);
                }
            }

            // we only want to add fields which are not used in the route
            if (uri != null && findEndpointByUri(endpoints, uri) == null) {

                // we only want the relative dir name from the
                String fileName = fullyQualifiedFileName;
                if (fileName.startsWith(baseDir)) {
                    fileName = fileName.substring(baseDir.length() + 1);
                }
                String id = field.getName();

                CamelEndpointDetails detail = new CamelEndpointDetails();
                detail.setFileName(fileName);
                detail.setClassName(clazz.getQualifiedName());
                detail.setEndpointInstance(id);
                detail.setEndpointUri(uri);
                detail.setEndpointComponentName(endpointComponentName(uri));
                // we do not know if this field is used as consumer or producer only, but we try
                // to find out by scanning the route in the configure method below
                endpoints.add(detail);
            }
        }

        // look if any of these fields are used in the route only as consumer or producer, as then we can
        // determine this to ensure when we edit the endpoint we should only the options accordingly
        MethodSource<JavaClassSource> method = CamelJavaParserHelper.findConfigureMethod(clazz);
        if (method != null) {
            // consumers only
            List<ParserResult> uris = CamelJavaParserHelper.parseCamelConsumerUris(method, false, true);
            for (ParserResult uri : uris) {
                CamelEndpointDetails detail = findEndpointByUri(endpoints, uri.getElement());
                if (detail != null) {
                    // its a consumer only
                    detail.setConsumerOnly(true);
                }
            }
            // producer only
            uris = CamelJavaParserHelper.parseCamelProducerUris(method, false, true);
            for (ParserResult result : uris) {
                CamelEndpointDetails detail = findEndpointByUri(endpoints, result.getElement());
                if (detail != null) {
                    if (detail.isConsumerOnly()) {
                        // its both a consumer and producer
                        detail.setConsumerOnly(false);
                        detail.setProducerOnly(false);
                    } else {
                        // its a producer only
                        detail.setProducerOnly(true);
                    }
                }
            }

            // look for endpoints in the configure method that are string based
            // consumers only
            uris = CamelJavaParserHelper.parseCamelConsumerUris(method, true, false);
            for (ParserResult result : uris) {
                String fileName = fullyQualifiedFileName;
                if (fileName.startsWith(baseDir)) {
                    fileName = fileName.substring(baseDir.length() + 1);
                }

                CamelEndpointDetails detail = new CamelEndpointDetails();
                detail.setFileName(fileName);
                detail.setClassName(clazz.getQualifiedName());
                detail.setMethodName("configure");
                detail.setEndpointInstance(null);
                detail.setEndpointUri(result.getElement());
                int line = findLineNumber(fullyQualifiedFileName, result.getPosition());
                if (line > -1) {
                    detail.setLineNumber("" + line);
                }
                detail.setEndpointComponentName(endpointComponentName(result.getElement()));
                detail.setConsumerOnly(true);
                detail.setProducerOnly(false);
                endpoints.add(detail);
            }
            uris = CamelJavaParserHelper.parseCamelProducerUris(method, true, false);
            for (ParserResult result : uris) {
                // the same uri may already have been used as consumer as well
                CamelEndpointDetails detail = findEndpointByUri(endpoints, result.getElement());
                if (detail == null) {
                    // its a producer only uri
                    String fileName = fullyQualifiedFileName;
                    if (fileName.startsWith(baseDir)) {
                        fileName = fileName.substring(baseDir.length() + 1);
                    }

                    detail = new CamelEndpointDetails();
                    detail.setFileName(fileName);
                    detail.setClassName(clazz.getQualifiedName());
                    detail.setMethodName("configure");
                    detail.setEndpointInstance(null);
                    detail.setEndpointUri(result.getElement());
                    int line = findLineNumber(fullyQualifiedFileName, result.getPosition());
                    if (line > -1) {
                        detail.setLineNumber("" + line);
                    }
                    detail.setEndpointComponentName(endpointComponentName(result.getElement()));
                    detail.setConsumerOnly(false);
                    detail.setProducerOnly(true);

                    endpoints.add(detail);
                } else {
                    // we already have this uri as a consumer, then mark it as both consumer+producer
                    detail.setConsumerOnly(false);
                    detail.setProducerOnly(false);
                }
            }
        }
    }

    private static CamelEndpointDetails findEndpointByUri(List<CamelEndpointDetails> endpoints, String uri) {
        for (CamelEndpointDetails detail : endpoints) {
            if (uri.equals(detail.getEndpointUri())) {
                return detail;
            }
        }
        return null;
    }

    public static void parseRouteBuilderSimpleExpressions(JavaClassSource clazz, String baseDir, String fullyQualifiedFileName,
                                                          List<CamelSimpleDetails> simpleExpressions) {

        // must be a route builder class (or from spring-boot)
        // TODO: we should look up the type hierachy if possible
        String superType = clazz.getSuperType();
        if (superType != null) {
            boolean valid = "org.apache.camel.builder.RouteBuilder".equals(superType)
                    || "org.apache.camel.spring.boot.FatJarRouter".equals(superType);
            if (!valid) {
                return;
            }
        }

        MethodSource<JavaClassSource> method = CamelJavaParserHelper.findConfigureMethod(clazz);
        if (method != null) {
            List<ParserResult> expressions = CamelJavaParserHelper.parseCamelSimpleExpressions(method);
            for (ParserResult result : expressions) {

                String fileName = fullyQualifiedFileName;
                if (fileName.startsWith(baseDir)) {
                    fileName = fileName.substring(baseDir.length() + 1);
                }

                CamelSimpleDetails details = new CamelSimpleDetails();
                details.setFileName(fileName);
                details.setClassName(clazz.getQualifiedName());
                details.setMethodName("configure");
                int line = findLineNumber(fullyQualifiedFileName, result.getPosition());
                if (line > -1) {
                    details.setLineNumber("" + line);
                }
                details.setSimple(result.getElement());

                simpleExpressions.add(details);
            }
        }
    }

    private static int findLineNumber(String fullyQualifiedFileName, int position) {
        // TODO: Next version of roaster has this out of the box (LocationCapable)
        int lines = 0;

        try {
            int current = 0;
            try (BufferedReader br = new BufferedReader(new FileReader(new File(fullyQualifiedFileName)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines++;
                    current += line.length() + 1; // add 1 for line feed
                    if (current >= position) {
                        return lines;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
            return -1;
        }

        return lines;
    }

}
