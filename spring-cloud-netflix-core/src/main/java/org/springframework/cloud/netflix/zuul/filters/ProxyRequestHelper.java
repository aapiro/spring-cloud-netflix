/*
 * Copyright 2013-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.zuul.filters;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;

import org.springframework.boot.actuate.trace.TraceRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.util.UriTemplate;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.WebUtils;

import com.netflix.zuul.context.RequestContext;
import com.netflix.zuul.util.HTTPRequestUtils;

import static org.springframework.http.HttpHeaders.CONTENT_ENCODING;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;

import lombok.extern.apachecommons.CommonsLog;

/**
 * @author Dave Syer
 */
@CommonsLog
public class ProxyRequestHelper {

	/**
	 * Zuul context key for a collection of ignored headers for the current request.
	 * Pre-filters can set this up as a set of lowercase strings.
	 */
	public static final String IGNORED_HEADERS = "ignoredHeaders";

	private TraceRepository traces;

	private Set<String> ignoredHeaders = new LinkedHashSet<>();

	private Set<String> sensitiveHeaders = new LinkedHashSet<>();

	private Set<String> whitelistHosts = new LinkedHashSet<>();

	public void setWhitelistHosts(Set<String> whitelistHosts) {
		this.whitelistHosts.addAll(whitelistHosts);
	}

	public void setSensitiveHeaders(Set<String> sensitiveHeaders) {
		this.sensitiveHeaders.addAll(sensitiveHeaders);
	}

	public void setIgnoredHeaders(Set<String> ignoredHeaders) {
		this.ignoredHeaders.addAll(ignoredHeaders);
	}

	public void setTraces(TraceRepository traces) {
		this.traces = traces;
	}

	public String buildZuulRequestURI(HttpServletRequest request) {
		RequestContext context = RequestContext.getCurrentContext();
		String uri = request.getRequestURI();
		String contextURI = (String) context.get("requestURI");
		if (contextURI != null) {
			try {
				uri = UriUtils.encodePath(contextURI,
						WebUtils.DEFAULT_CHARACTER_ENCODING);
			}
			catch (Exception e) {
				log.debug(
						"unable to encode uri path from context, falling back to uri from request",
						e);
			}
		}
		return uri;
	}

	public MultiValueMap<String, String> buildZuulRequestQueryParams(
			HttpServletRequest request) {
		Map<String, List<String>> map = HTTPRequestUtils.getInstance().getQueryParams();
		MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
		if (map == null) {
			return params;
		}
		for (String key : map.keySet()) {
			for (String value : map.get(key)) {
				params.add(key, value);
			}
		}
		return params;
	}

	public MultiValueMap<String, String> buildZuulRequestHeaders(
			HttpServletRequest request) {
		RequestContext context = RequestContext.getCurrentContext();
		MultiValueMap<String, String> headers = new HttpHeaders();
		Enumeration<String> headerNames = request.getHeaderNames();
		if (headerNames != null) {
			while (headerNames.hasMoreElements()) {
				String name = headerNames.nextElement();
				if (isIncludedHeader(name)) {
					Enumeration<String> values = request.getHeaders(name);
					while (values.hasMoreElements()) {
						String value = values.nextElement();
						headers.add(name, value);
					}
				}
			}
		}
		Map<String, String> zuulRequestHeaders = context.getZuulRequestHeaders();
		for (String header : zuulRequestHeaders.keySet()) {
			headers.set(header, zuulRequestHeaders.get(header));
		}
		headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip");
		return headers;
	}

	public void setResponse(int status, InputStream entity,
			MultiValueMap<String, String> headers) throws IOException {
		RequestContext context = RequestContext.getCurrentContext();
		context.setResponseStatusCode(status);
		if (entity != null) {
			context.setResponseDataStream(entity);
		}

		HttpHeaders httpHeaders = new HttpHeaders();
		for (Entry<String, List<String>> header : headers.entrySet()) {
			List<String> values = header.getValue();
			for (String value : values) {
				httpHeaders.add(header.getKey(), value);
			}
		}
		boolean isOriginResponseGzipped = false;
		if (httpHeaders.containsKey(CONTENT_ENCODING)) {
			List<String> collection = httpHeaders.get(CONTENT_ENCODING);
			for (String header : collection) {
				if (HTTPRequestUtils.getInstance().isGzipped(header)) {
					isOriginResponseGzipped = true;
					break;
				}
			}
		}
		context.setResponseGZipped(isOriginResponseGzipped);

		for (Entry<String, List<String>> header : headers.entrySet()) {
			String name = header.getKey();
			for (String value : header.getValue()) {
				context.addOriginResponseHeader(name, value);
				if (name.equalsIgnoreCase(CONTENT_LENGTH)) {
					context.setOriginContentLength(value);
				}
				if (isIncludedHeader(name)) {
					context.addZuulResponseHeader(name, value);
				}
			}
		}
	}

	public void addIgnoredHeaders(String... names) {
		RequestContext ctx = RequestContext.getCurrentContext();
		if (!ctx.containsKey(IGNORED_HEADERS)) {
			ctx.set(IGNORED_HEADERS, new HashSet<String>());
		}
		@SuppressWarnings("unchecked")
		Set<String> set = (Set<String>) ctx.get(IGNORED_HEADERS);
		for (String name : this.ignoredHeaders) {
			set.add(name.toLowerCase());
		}
		for (String name : getSensitiveHeaders(ctx)) {
			set.add(name.toLowerCase());
		}
		for (String name : names) {
			set.add(name.toLowerCase());
		}
	}

	private Collection<String> getSensitiveHeaders(RequestContext ctx) {
		URL uri = ctx.getRouteHost();
		if (uri == null) {
			return Collections.emptySet();
		}
		String host;
		host = uri.getHost();
		if (PatternMatchUtils.simpleMatch(this.whitelistHosts.toArray(new String[0]),
				host)) {
			return this.sensitiveHeaders;
		}
		return Collections.emptySet();
	}

	public boolean isIncludedHeader(String headerName) {
		String name = headerName.toLowerCase();
		RequestContext ctx = RequestContext.getCurrentContext();
		if (ctx.containsKey(IGNORED_HEADERS)) {
			Object object = ctx.get(IGNORED_HEADERS);
			if (object instanceof Collection && ((Collection<?>) object).contains(name)) {
				return false;
			}
		}
		switch (name) {
		case "host":
		case "connection":
		case "content-length":
		case "content-encoding":
		case "server":
		case "transfer-encoding":
		case "x-application-context":
			return false;
		default:
			return true;
		}
	}

	public Map<String, Object> debug(String verb, String uri,
			MultiValueMap<String, String> headers, MultiValueMap<String, String> params,
			InputStream requestEntity) throws IOException {
		Map<String, Object> info = new LinkedHashMap<String, Object>();
		if (this.traces != null) {
			RequestContext context = RequestContext.getCurrentContext();
			info.put("method", verb);
			info.put("path", uri);
			info.put("query", getQueryString(params));
			info.put("remote", true);
			info.put("proxy", context.get("proxy"));
			Map<String, Object> trace = new LinkedHashMap<String, Object>();
			Map<String, Object> input = new LinkedHashMap<String, Object>();
			trace.put("request", input);
			info.put("headers", trace);
			for (Entry<String, List<String>> entry : headers.entrySet()) {
				Collection<String> collection = entry.getValue();
				Object value = collection;
				if (collection.size() < 2) {
					value = collection.isEmpty() ? "" : collection.iterator().next();
				}
				input.put(entry.getKey(), value);
			}
			RequestContext ctx = RequestContext.getCurrentContext();
			if (shouldDebugBody(ctx)) {
				// Prevent input stream from being read if it needs to go downstream
				if (requestEntity != null) {
					debugRequestEntity(info, ctx.getRequest().getInputStream());
				}
			}
			this.traces.add(info);
			return info;
		}
		return info;
	}

	private boolean shouldDebugBody(RequestContext ctx) {
		HttpServletRequest request = ctx.getRequest();
		if (ctx.isChunkedRequestBody()) {
			return false;
		}
		if (request == null || request.getContentType() == null) {
			return true;
		}
		return !request.getContentType().toLowerCase().contains("multipart");
	}

	public void appendDebug(Map<String, Object> info, int status,
			MultiValueMap<String, String> headers) {
		if (this.traces != null) {
			@SuppressWarnings("unchecked")
			Map<String, Object> trace = (Map<String, Object>) info.get("headers");
			Map<String, Object> output = new LinkedHashMap<String, Object>();
			trace.put("response", output);
			for (Entry<String, List<String>> key : headers.entrySet()) {
				Collection<String> collection = key.getValue();
				Object value = collection;
				if (collection.size() < 2) {
					value = collection.isEmpty() ? "" : collection.iterator().next();
				}
				output.put(key.getKey(), value);
			}
			output.put("status", "" + status);
		}
	}

	private void debugRequestEntity(Map<String, Object> info, InputStream inputStream)
			throws IOException {
		if (RequestContext.getCurrentContext().isChunkedRequestBody()) {
			info.put("body", "<chunked>");
			return;
		}
		char[] buffer = new char[4096];
		int count = new InputStreamReader(inputStream, Charset.forName("UTF-8"))
				.read(buffer, 0, buffer.length);
		if (count > 0) {
			String entity = new String(buffer).substring(0, count);
			info.put("body", entity.length() < 4096 ? entity : entity + "<truncated>");
		}
	}

	public String getQueryString(MultiValueMap<String, String> params) {
		if (params.isEmpty()) {
			return "";
		}
		StringBuilder query = new StringBuilder();
		Map<String, Object> singles = new HashMap<>();
		for (String param : params.keySet()) {
			int i = 0;
			for (String value : params.get(param)) {
				query.append("&");
				query.append(param);
				if (!"".equals(value)) {
					singles.put(param + i, value);
					query.append("={");
					query.append(param + i);
					query.append("}");
				}
				i++;
			}
		}

		UriTemplate template = new UriTemplate("?" + query.toString().substring(1));
		return template.expand(singles).toString();
	}
}
