/*
 * Copyright 2010 the original author or authors.
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
package org.springframework.social.connect;

import java.util.Collection;
import java.util.List;

import org.scribe.extractors.BaseStringExtractorImpl;
import org.scribe.extractors.HeaderExtractorImpl;
import org.scribe.extractors.TokenExtractorImpl;
import org.scribe.model.OAuthConfig;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.model.Verifier;
import org.scribe.oauth.OAuth10aServiceImpl;
import org.scribe.oauth.OAuthService;
import org.scribe.services.HMACSha1SignatureService;
import org.scribe.services.TimestampServiceImpl;
import org.springframework.transaction.annotation.Transactional;

/**
 * General-purpose base class for ServiceProvider implementations.
 * @author Keith Donald
 * @param <S> The service API hosted by this service provider.
 */
public abstract class AbstractServiceProvider<S> implements ServiceProvider<S> {
	
	private final ServiceProviderParameters parameters;

	private final AccountConnectionRepository connectionRepository;

	private final AccountIdResolver accountIdResolver;
	
	/**
	 * Creates a ServiceProvider.
	 * @param parameters the parameters needed to implement the behavior in this class
	 * @param connectionRepository a data access interface for managing account connection records
	 */
	public AbstractServiceProvider(ServiceProviderParameters parameters,
			AccountConnectionRepository connectionRepository, AccountIdResolver accountIdResolver) {
		this.parameters = parameters;
		this.connectionRepository = connectionRepository;
		this.accountIdResolver = accountIdResolver;
	}

	// provider meta-data
	
	public String getName() {
		return parameters.getName();
	}
	
	public String getDisplayName() {
		return parameters.getDisplayName();
	}
	
	public String getApiKey() {
		return parameters.getApiKey();
	}

	public Long getAppId() {
		return parameters.getAppId();
	}
	
	// connection management
	
	public OAuthToken fetchNewRequestToken(String callbackUrl) {
		Token requestToken = getOAuthService(callbackUrl).getRequestToken();
		return new OAuthToken(requestToken.getToken(), requestToken.getSecret());
	}

	public String buildAuthorizeUrl(String requestToken) {
		return parameters.getAuthorizeUrl().expand(requestToken).toString();
	}

	public OAuthToken connect(AuthorizedRequestToken requestToken) {
		OAuthToken accessToken = getAccessToken(requestToken);
		S serviceOperations = createServiceOperations(accessToken);
		String providerAccountId = fetchProviderAccountId(serviceOperations);
		connectionRepository.addConnection(accountIdResolver.resolveAccountId(), getName(), accessToken,
				providerAccountId,
				buildProviderProfileUrl(providerAccountId, serviceOperations));
		return accessToken;
	}

	public void addConnection(String accessToken, String providerAccountId) {
		OAuthToken oauthAccessToken = new OAuthToken(accessToken);
		S serviceOperations = createServiceOperations(oauthAccessToken);
		connectionRepository.addConnection(accountIdResolver.resolveAccountId(), getName(), oauthAccessToken,
				providerAccountId,
				buildProviderProfileUrl(providerAccountId, serviceOperations));
	}

	public boolean isConnected() {
		return connectionRepository.isConnected(accountIdResolver.resolveAccountId(), getName());
	}

	public void disconnect() {
		connectionRepository.disconnect(accountIdResolver.resolveAccountId(), getName());
	}
	
	@Transactional
	public S getServiceOperations() {
		if (accountIdResolver.resolveAccountId() == null || !isConnected()) {
			return createServiceOperations(null);
		}
		OAuthToken accessToken = connectionRepository.getAccessToken(accountIdResolver.resolveAccountId(), getName());
		return createServiceOperations(accessToken);
	}

	public S getServiceOperations(OAuthToken accessToken) {
		return createServiceOperations(accessToken);
	}

	public Collection<AccountConnection> getConnections() {
		return connectionRepository.getAccountConnections(accountIdResolver.resolveAccountId(), getName());
	}

	// additional finders
	
	public String getProviderAccountId() {
		return connectionRepository.getProviderAccountId(accountIdResolver.resolveAccountId(), getName());
	}

	// subclassing hooks
	
	/**
	 * Construct the strongly-typed service API template that callers may use to invoke the service offered by this service provider.
	 * Subclasses should override to return their concrete service implementation.
	 * @param accessToken the granted access token needed to make authorized requests for protected resources
	 */
	protected abstract S createServiceOperations(OAuthToken accessToken);

	/**
	 * Use the service API to fetch the id the member has been assigned in the provider's system.
	 * This id is stored locally to support linking to the user's connected profile page.
	 * It is also used for finding connected friends, see {@link #findMembersConnectedTo(List)}.
	 */
	protected abstract String fetchProviderAccountId(S serviceOperations);

	/**
	 * Build the URL pointing to the member's public profile on the provider's system.
	 * @param providerAccountId the id the member is known by in the provider's system.
	 * @param serviceOperations the service API
	 */
	protected abstract String buildProviderProfileUrl(String providerAccountId, S serviceOperations);

	/**
	 * The {@link #getApiKey() apiKey} secret.
	 */
	protected String getSecret() {
		return parameters.getSecret();
	}
	

	// internal helpers
	
	private OAuthService getOAuthService() {
		return getOAuthService(null);
	}
	
	private OAuthService getOAuthService(String callbackUrl) {
		OAuthConfig config = new OAuthConfig();
		config.setRequestTokenEndpoint(parameters.getRequestTokenUrl());
		config.setAccessTokenEndpoint(parameters.getAccessTokenUrl());
		config.setAccessTokenVerb(Verb.POST);
		config.setRequestTokenVerb(Verb.POST);
		config.setApiKey(parameters.getApiKey());
		config.setApiSecret(parameters.getSecret());
		if (callbackUrl != null) {
			config.setCallback(callbackUrl);
		}
		return new OAuth10aServiceImpl(new HMACSha1SignatureService(), new TimestampServiceImpl(), new BaseStringExtractorImpl(), new HeaderExtractorImpl(), new TokenExtractorImpl(), new TokenExtractorImpl(), config);
	}

	private OAuthToken getAccessToken(AuthorizedRequestToken requestToken) {
		Token accessToken = getOAuthService().getAccessToken(new Token(requestToken.getValue(), requestToken.getSecret()), new Verifier(requestToken.getVerifier()));
		return new OAuthToken(accessToken.getToken(), accessToken.getSecret());
	}

}